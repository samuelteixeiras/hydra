package hydra.core.test

import hydra.core.ingest.HydraRequest
import hydra.core.transport._

import scala.concurrent.{ExecutionContext, Future}

object TestRecordFactory extends RecordFactory[String, String] {
  override def build(r: HydraRequest)(implicit ec: ExecutionContext) = {
    val timeout = r.metadataValueEquals("timeout", "true")
    if (timeout) {
      Future.successful(TimeoutRecord("test-topic", Some(r.correlationId.toString), r.payload,
        r.ackStrategy))
    }
    else {
      Future.successful(TestRecord("test-topic", Some(r.correlationId.toString), r.payload,
        r.ackStrategy))
    }
  }
}

case class TestRecord(destination: String,
                      key: Option[String],
                      payload: String,
                      ackStrategy: AckStrategy) extends HydraRecord[String, String]


case class TestRecordMetadata(deliveryId: Long, timestamp: Long = System.currentTimeMillis) extends RecordMetadata

case class TimeoutRecord(destination: String,
                         key: Option[String],
                         payload: String,
                         ackStrategy: AckStrategy) extends HydraRecord[String, String]