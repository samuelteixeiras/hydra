package hydra.avro.io

import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord

sealed trait Operation {
  def shouldFlush: Boolean = false
}

case class Upsert(record: GenericRecord) extends Operation

case class DeleteByKey(keys: Map[Schema.Field, AnyRef]) extends Operation {
  override val shouldFlush: Boolean = true
}