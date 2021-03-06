package hydra.kafka.services

import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.Status.{Failure => AkkaFailure}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, Props, Stash, Timers}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.typesafe.config.Config
import configs.syntax._
import hydra.avro.registry.ConfluentSchemaRegistry
import hydra.common.config.ConfigSupport
import hydra.core.akka.SchemaRegistryActor.{RegisterSchemaRequest, RegisterSchemaResponse}
import hydra.core.ingest.{HydraRequest, RequestParams}
import hydra.core.marshallers.{HydraJsonSupport, TopicMetadataRequest}
import hydra.core.protocol.{Ingest, IngestorCompleted, IngestorError}
import hydra.core.transport.{AckStrategy, ValidationStrategy}
import hydra.kafka.model.TopicMetadata
import hydra.kafka.producer.{AvroRecord, AvroRecordFactory}
import hydra.kafka.services.MetadataConsumerActor.{GetMetadata, GetMetadataResponse, StopStream}
import hydra.kafka.util.KafkaUtils
import org.apache.kafka.common.requests.CreateTopicsRequest.TopicDetails

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.Source
import scala.util._

class TopicBootstrapActor(schemaRegistryActor: ActorRef,
                          kafkaIngestor: ActorSelection,
                          bootstrapConfig: Option[Config] = None,
                          consumerProps: Props) extends Actor
  with ActorLogging
  with ConfigSupport
  with HydraJsonSupport
  with Stash
  with Timers {

  import TopicBootstrapActor._
  import spray.json._

  implicit val metadataFormat = jsonFormat10(TopicMetadata)

  implicit val ec = context.dispatcher

  implicit val timeout = Timeout(10.seconds)

  val schema = Source.fromResource("HydraMetadataTopic.avsc").mkString

  private val kafkaUtils = KafkaUtils()

  val bootstrapKafkaConfig: Config = bootstrapConfig getOrElse
    applicationConfig.getConfig("bootstrap-config")

  private val metadataTopicName = getMetadataTopicName(bootstrapKafkaConfig)

  private val metadataStreamActor = context.actorOf(consumerProps)

  override def preStart(): Unit = {
    pipe(registerSchema(schema)) to self
  }

  override def postStop(): Unit = {
    metadataStreamActor ! StopStream
  }

  val topicDetailsConfig: util.Map[String, String] = Map[String, String]().empty.asJava

  val topicDetails = new TopicDetails(
    bootstrapKafkaConfig.getInt("partitions"),
    bootstrapKafkaConfig.getInt("replication-factor").toShort,
    topicDetailsConfig)

  private val failureRetryInterval = bootstrapKafkaConfig
    .get[Int]("failure-retry-millis")
    .value

  override def receive: Receive = initializing

  def initializing: Receive = {
    case RegisterSchemaResponse(_) =>
      context.become(active)
      unstashAll()

    case AkkaFailure(ex) =>
      log.error(s"TopicBootstrapActor entering failed state due to: ${ex.getMessage}")
      unstashAll()
      timers.startSingleTimer("retry-failure", Retry, failureRetryInterval.millis)
      context.become(failed(ex))

    case _ => stash()
  }

  def active: Receive = {
    case InitiateTopicBootstrap(topicMetadataRequest) =>
      TopicNameValidator.validate(topicMetadataRequest.subject) match {
        case Success(_) =>
          val result = for {
            schema <- registerSchema(topicMetadataRequest.schema.compactPrint)
            topicMetadata <- ingestMetadata(topicMetadataRequest, schema.schemaResource.id)
            bootstrapResult <- createKafkaTopic(topicMetadata)
          } yield bootstrapResult

          pipe(
            result.recover {
              case t: Throwable => BootstrapFailure(Seq(t.getMessage))
            }) to sender

        case Failure(ex: TopicNameValidatorException) =>
          Future(BootstrapFailure(ex.reasons)) pipeTo sender
      }

    case GetStreams(subject) =>
      val streams: Future[GetStreamsResponse] = (metadataStreamActor ? GetMetadata).mapTo[GetMetadataResponse]
        .map { x =>
          val resp = subject.map(s => x.metadata.values.filter(p => p.subject == s)) getOrElse x.metadata.values
          GetStreamsResponse(resp.toSeq)
        }

      pipe(streams) to sender
  }

  def failed(ex: Throwable): Receive = {
    case Retry =>
      log.info("Retrying metadata schema registration...")
      context.become(initializing)
      pipe(registerSchema(schema)) to self

    case _ =>
      val failureMessage = s"TopicBootstrapActor is in a failed state due to cause: ${ex.getMessage}"
      Future.failed(new Exception(failureMessage)) pipeTo sender
  }

  private[kafka] def registerSchema(schemaJson: String): Future[RegisterSchemaResponse] = {
    (schemaRegistryActor ? RegisterSchemaRequest(schemaJson)).mapTo[RegisterSchemaResponse]
  }

  private[kafka] def ingestMetadata(topicMetadataRequest: TopicMetadataRequest,
                                    schemaId: Int): Future[TopicMetadata] = {

    val topicMetadata = TopicMetadata(
      topicMetadataRequest.subject,
      schemaId,
      topicMetadataRequest.streamType,
      topicMetadataRequest.derived,
      topicMetadataRequest.dataClassification,
      topicMetadataRequest.contact,
      topicMetadataRequest.additionalDocumentation,
      topicMetadataRequest.notes,
      UUID.randomUUID(),
      org.joda.time.DateTime.now())

    buildAvroRecord(topicMetadata).flatMap { record =>
      (kafkaIngestor ? Ingest(record, record.ackStrategy)).map {
        case IngestorCompleted => topicMetadata
        case IngestorError(ex) =>
          val errorMessage = ex.getMessage
          log.error(
            s"TopicBootstrapActor received an IngestorError from KafkaIngestor: $errorMessage")
          throw ex
      }
    }.recover {
      case ex: Throwable =>
        val errorMessage = ex.getMessage
        log.error(s"Unexpected error occurred during initiateBootstrap: $errorMessage")
        throw ex
    }
  }

  private[kafka] def buildAvroRecord(metadata: TopicMetadata): Future[AvroRecord] = {

    val jsonString = metadata.toJson.compactPrint
    new AvroRecordFactory(schemaRegistryActor).build(
      HydraRequest(
        "0",
        jsonString,
        metadata = Map(
          RequestParams.HYDRA_KAFKA_TOPIC_PARAM -> metadataTopicName),
        ackStrategy = AckStrategy.Replicated,
        validationStrategy = ValidationStrategy.Strict
      )
    )
  }

  private[kafka] def createKafkaTopic(metadata: TopicMetadata): Future[BootstrapResult] = {
    val timeoutMillis = bootstrapKafkaConfig.getInt("timeout")

    val topic = metadata.subject

    val topicExists = kafkaUtils.topicExists(topic) match {
      case Success(value) => value
      case Failure(exception) =>
        log.error(s"Unable to determine if topic exists: ${exception.getMessage}")
        return Future.failed(exception)
    }

    // Don't fail when topic already exists
    if (topicExists) {
      log.info(s"Topic $topic already exists, proceeding anyway...")
      Future.successful(BootstrapSuccess(metadata))
    }
    else {
      kafkaUtils.createTopic(metadata.subject, topicDetails, timeout = timeoutMillis)
        .map { r =>
          r.all.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        .map { _ =>
          BootstrapSuccess(metadata)
        }
        .recover {
          case e: Exception => BootstrapFailure(e.getMessage :: Nil)
        }
    }
  }
}

object TopicBootstrapActor {

  def getMetadataTopicName(c: Config) = c.get[String]("metadata-topic-name")
    .valueOrElse("_hydra.metadata.topic")

  def props(schemaRegistryActor: ActorRef, kafkaIngestor: ActorSelection,
            consumerProps: Props,
            config: Option[Config] = None): Props =
    Props(classOf[TopicBootstrapActor], schemaRegistryActor, kafkaIngestor, config, consumerProps)

  sealed trait TopicBootstrapMessage

  case class InitiateTopicBootstrap(topicMetadataRequest: TopicMetadataRequest) extends TopicBootstrapMessage

  case class ForwardBootstrapPayload(request: HydraRequest) extends TopicBootstrapMessage

  sealed trait BootstrapResult

  case class BootstrapSuccess(metadata: TopicMetadata) extends BootstrapResult

  case class BootstrapFailure(reasons: Seq[String]) extends BootstrapResult

  case class BootstrapStep[A](stepResult: A) extends BootstrapResult

  /**
    * Filter by subject is the only supported.
    *
    * @param subject
    */
  case class GetStreams(subject: Option[String]) extends TopicBootstrapMessage

  case class GetStreamsResponse(metadata: Seq[TopicMetadata]) extends BootstrapResult

  case object Retry

}
