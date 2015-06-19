package report.data

import report.DisplayAsJson
import com.mongodb.DBObject
import com.mongodb.BasicDBObject
import com.mongodb.BasicDBList

import collection.JavaConversions._


class TapeString(val text: String, val markup: Map[(Int, Int), Any]=Map.empty) extends DisplayAsJson {
  import TapeString._
  def +(that: TapeString) = TapeString(text + that.text, markup ++ shift(that.markup, text.length))
  def +:(that: TapeString) = that + this
  
  def |-|(mark: Any) = TapeString(text, markup + ((0 -> text.length) -> mark))
  
  override def toString = text
  
  def displayAsJson: DBObject = {
    val markupList = new BasicDBList()
    markupList.addAll(markup.keys.toList sortBy (_._1) map (x => pair(x)))
    new BasicDBObject("tape", new BasicDBObject("text", text).append("markup", markupList))
  }
  
  def pair[A,B](x: (A, B)): java.util.Collection[Any] = List(x._1, x._2)
}

object TapeString {
  def apply(text: String, markup: Map[(Int, Int), Any]=Map.empty) = new TapeString(text, markup)
  
  implicit def fromAny(text: Any) = text match {
    case x: TapeString => x
    case _ => TapeString(text.toString)
  }
  
  def shift(markup: Map[(Int, Int), Any], offset: Int) =
    markup map { case ((from, to), v) => ((from + offset, to + offset), v) } toMap

  implicit class TapeFormat(val sc: StringContext) extends AnyVal {
    def tape(args: Any*) = {
      (TapeString(sc.parts.head) /: (args zip sc.parts.tail)) { 
        case (before, (arg, after)) => before + arg + after 
      }
    }
  }
    
  implicit class TapeJoin(val tapes: List[TapeString]) extends AnyVal {
    def mkTapeString(sep: TapeString) = (tapes.head /: tapes.tail)(_ + sep + _)
  }

}