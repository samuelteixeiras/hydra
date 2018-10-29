package hydra.ingest.services

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import hydra.avro.resource.SchemaResource
import hydra.core.akka.SchemaRegistryActor.{FetchSchemaRequest, FetchSchemaResponse, RegisterSchemaRequest, RegisterSchemaResponse}
import hydra.core.marshallers.TopicMetadataRequest
import hydra.core.protocol.Ingest
import hydra.core.transport.{AckStrategy, HydraRecord}
import hydra.ingest.http.HydraIngestJsonSupport
import hydra.ingest.services.TopicBootstrapActor.{BootstrapFailure, InitiateTopicBootstrap}
import hydra.kafka.producer.AvroRecord
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json._

import scala.concurrent.Future

class TopicBootstrapActorSpec extends TestKit(ActorSystem("topic-bootstrap-actor-spec"))
  with FlatSpecLike
  with Matchers
  with BeforeAndAfterAll
  with MockFactory
  with HydraIngestJsonSupport
  with Eventually
  with ImplicitSender {

  import hydra.ingest.services.ErrorMessages._

  implicit val ec = system.dispatcher

  override def afterAll(): Unit = {
    super.afterAll()
    TestKit.shutdownActorSystem(system)
  }

  val config = ConfigFactory.load()

  val testJson =
    """
      |{
      |  "namespace": "hydra.metadata",
      |  "name": "topic",
      |  "type": "record",
      |  "version": 1,
      |  "fields": [
      |    {
      |      "name": "streamName",
      |      "type": "string"
      |    },
      |    {
      |      "name": "streamType",
      |      "type": "string"
      |    },
      |    {
      |      "name": "streamSubType",
      |      "type": "string"
      |    },
      |    {
      |      "name": "dataClassification",
      |      "type": "string"
      |    },
      |    {
      |      "name": "dataSourceOwner",
      |      "type": "string"
      |    },
      |    {
      |      "name": "dataSourceContact",
      |      "type": "string"
      |    },
      |    {
      |      "name": "psDataLake",
      |      "type": "boolean"
      |    },
      |    {
      |      "name": "dataDocPath",
      |      "type": "string"
      |    },
      |    {
      |    	"name": "dataOwnerNotes",
      |    	"type": "string"
      |    },
      |    {
      |    	"name": "streamSchema",
      |    	"type": "string"
      |    }
      |  ]
      |}
    """.stripMargin

  val testSchemaResource = SchemaResource(1, 1, new Schema.Parser().parse(testJson))

  def fixture(key: String, kafkaShouldFail: Boolean = false,
              schemaRegistryShouldFail: Boolean = false) = {
    val probe = TestProbe()

    val schemaRegistryActor = system.actorOf(
      Props(
        new Actor {
          override def receive: Receive = {
            case msg: FetchSchemaRequest =>
              sender ! FetchSchemaResponse(testSchemaResource)
              probe.ref forward msg

            case msg: RegisterSchemaRequest =>
              if (schemaRegistryShouldFail) {
                sender ! Failure(new Exception("Schema registry actor failed expectedly!"))
                probe.ref forward msg
              } else {
                sender ! RegisterSchemaResponse(testSchemaResource)
                probe.ref forward msg
              }
          }
        }
      )
    )

    val kafkaIngestor = system.actorOf(Props(
      new Actor {
        override def receive = {
          case msg: Ingest[_, _] =>
            probe.ref forward msg
            if (kafkaShouldFail) {
              Future.failed(new Exception("Kafka ingestor failed expectedly!")) pipeTo sender
            }
        }
      }
    ), s"kafka_ingestor_$key")

    (probe, schemaRegistryActor, kafkaIngestor)
  }

  "A TopicBootstrapActor" should "process metadata and send an Ingest message to the kafka ingestor" in {

    val mdRequest = """{
                      |	"streamName": "exp.dataplatform.testsubject",
                      |	"streamType": "Historical",
                      |	"streamSubType": "Source Of Truth",
                      |	"dataClassification": "Public",
                      |	"dataSourceOwner": "BARTON",
                      |	"dataSourceContact": "slackity slack dont talk back",
                      |	"psDataLake": false,
                      |	"dataDocPath": "akka://some/path/here.jpggifyo",
                      |	"dataOwnerNotes": "here are some notes topkek",
                      |	"streamSchema": {
                      |	  "namespace": "exp.assessment",
                      |	  "name": "SkillAssessmentTopicsScored",
                      |	  "type": "record",
                      |	  "version": 1,
                      |	  "fields": [
                      |	    {
                      |	      "name": "test-field",
                      |	      "type": "string"
                      |	    }
                      |	  ]
                      |	}
                      |}"""
      .stripMargin
      .parseJson
      .convertTo[TopicMetadataRequest]

    val (probe, schemaRegistryActor, kafkaIngestor) = fixture("test1")

    val bootstrapActor = system.actorOf(TopicBootstrapActor.props(config, schemaRegistryActor,
      system.actorSelection("/user/kafka_ingestor_test1")))

    probe.expectMsgType[RegisterSchemaRequest]

    bootstrapActor ! InitiateTopicBootstrap(mdRequest)

    probe.receiveWhile(messages = 2) {
      case RegisterSchemaRequest(schemaJson) => schemaJson should
        include("SkillAssessmentTopicsScored")
      case FetchSchemaRequest(schemaName) => schemaName shouldEqual "hydra.metadata.topic"
    }

    probe.expectMsgPF() {
      case Ingest(msg: HydraRecord[_, GenericRecord], ack) =>
        msg shouldBe an[AvroRecord]
        msg.payload.getSchema.getName shouldBe "topic"
        ack shouldBe AckStrategy.Replicated
    }
  }

  it should "respond with the error that caused the failed actor state" in {
    val mdRequest = """{
                      |	"streamName": "exp",
                      |	"streamType": "Historical",
                      |	"streamSubType": "Source Of Truth",
                      |	"dataClassification": "Public",
                      |	"dataSourceOwner": "BARTON",
                      |	"dataSourceContact": "slackity slack dont talk back",
                      |	"psDataLake": false,
                      |	"dataDocPath": "akka://some/path/here.jpggifyo",
                      |	"dataOwnerNotes": "here are some notes topkek",
                      |	"streamSchema": {
                      |	  "namespace": "exp.assessment",
                      |	  "name": "SkillAssessmentTopicsScored",
                      |	  "type": "record",
                      |	  "version": 1,
                      |	  "fields": [
                      |	    {
                      |	      "name": "test-field",
                      |	      "type": "string"
                      |	    }
                      |	  ]
                      |	}
                      |}"""
      .stripMargin
      .parseJson
      .convertTo[TopicMetadataRequest]

    val (probe, schemaRegistryActor, _) = fixture("test2",
      schemaRegistryShouldFail = true)

    val bootstrapActor = system.actorOf(TopicBootstrapActor.props(config, schemaRegistryActor,
      system.actorSelection("kafka_ingestor_test2")))

    probe.expectMsgType[RegisterSchemaRequest]

    bootstrapActor ! InitiateTopicBootstrap(mdRequest)

    expectMsgPF() {
      case Failure(ex) => ex.getMessage shouldEqual
        "TopicBootstrapActor is in a failed state due to cause: Schema registry actor failed expectedly!"
    }
  }

  it should "respond with the appropriate metadata failure message" in {

    val mdRequest = """{
                      |	"streamName": "exp",
                      |	"streamType": "Historical",
                      |	"streamSubType": "Source Of Truth",
                      |	"dataClassification": "Public",
                      |	"dataSourceOwner": "BARTON",
                      |	"dataSourceContact": "slackity slack dont talk back",
                      |	"psDataLake": false,
                      |	"dataDocPath": "akka://some/path/here.jpggifyo",
                      |	"dataOwnerNotes": "here are some notes topkek",
                      |	"streamSchema": {
                      |	  "namespace": "exp.assessment",
                      |	  "name": "SkillAssessmentTopicsScored",
                      |	  "type": "record",
                      |	  "version": 1,
                      |	  "fields": [
                      |	    {
                      |	      "name": "test-field",
                      |	      "type": "string"
                      |	    }
                      |	  ]
                      |	}
                      |}"""
      .stripMargin
      .parseJson
      .convertTo[TopicMetadataRequest]

    val (probe, schemaRegistryActor, kafkaIngestor) = fixture("test3")

    val bootstrapActor = system.actorOf(TopicBootstrapActor.props(config, schemaRegistryActor,
      system.actorSelection("kafka_ingestor_test3")))

    probe.expectMsgType[RegisterSchemaRequest]

    bootstrapActor ! InitiateTopicBootstrap(mdRequest)

    expectMsgPF() {
      case BootstrapFailure(reasons) =>
        reasons should contain(BadTopicFormatError)
    }
  }

  it should "respond with the appropriate failure when the KafkaIngestor returns an exception" in {
    val mdRequest = """{
                      |	"streamName": "exp.something.something",
                      |	"streamType": "Historical",
                      |	"streamSubType": "Source Of Truth",
                      |	"dataClassification": "Public",
                      |	"dataSourceOwner": "BARTON",
                      |	"dataSourceContact": "slackity slack dont talk back",
                      |	"psDataLake": false,
                      |	"dataDocPath": "akka://some/path/here.jpggifyo",
                      |	"dataOwnerNotes": "here are some notes topkek",
                      |	"streamSchema": {
                      |	  "namespace": "exp.assessment",
                      |	  "name": "SkillAssessmentTopicsScored",
                      |	  "type": "record",
                      |	  "version": 1,
                      |	  "fields": [
                      |	    {
                      |	      "name": "test-field",
                      |	      "type": "string"
                      |	    }
                      |	  ]
                      |	}
                      |}"""
      .stripMargin
      .parseJson
      .convertTo[TopicMetadataRequest]

    val (probe, schemaRegistryActor, kafkaIngestor) = fixture("test4", kafkaShouldFail = true)

    val bootstrapActor = system.actorOf(TopicBootstrapActor.props(config, schemaRegistryActor,
      system.actorSelection("/user/kafka_ingestor_test4")))

    probe.expectMsgType[RegisterSchemaRequest]

    bootstrapActor ! InitiateTopicBootstrap(mdRequest)

    expectMsgPF() {
      case BootstrapFailure(reasons) =>
        reasons should contain("Kafka ingestor failed expectedly!")
    }
  }
}
