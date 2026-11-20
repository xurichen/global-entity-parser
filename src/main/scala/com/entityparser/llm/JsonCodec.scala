package com.entityparser.llm

import scala.collection.mutable

/** Minimal, zero-dependency JSON AST + parser + serializer for llama.cpp payloads. */
sealed trait JsValue {
  def asOptString: Option[String]            = None
  def asOptDouble: Option[Double]            = None
  def asOptBoolean: Option[Boolean]          = None
  def asOptObject: Option[Map[String, JsValue]] = None
  def asOptArray: Option[List[JsValue]]      = None
  def field(name: String): Option[JsValue]   = asOptObject.flatMap(_.get(name))
  def fieldString(name: String): Option[String] = field(name).flatMap(_.asOptString).filter(_.nonEmpty)
}

final case class JsObject(fields: List[(String, JsValue)]) extends JsValue {
  lazy val map: Map[String, JsValue] = fields.toMap
  override def asOptObject: Option[Map[String, JsValue]] = Some(map)
}
final case class JsArray(items: List[JsValue]) extends JsValue {
  override def asOptArray: Option[List[JsValue]] = Some(items)
}
final case class JsString(value: String) extends JsValue {
  override def asOptString: Option[String] = Some(value)
}
final case class JsNumber(value: Double) extends JsValue {
  override def asOptDouble: Option[Double] = Some(value)
}
final case class JsBoolean(value: Boolean) extends JsValue {
  override def asOptBoolean: Option[Boolean] = Some(value)
}
case object JsNull extends JsValue

object JsonCodec {

  def stringify(v: JsValue): String = v match {
    case JsNull       => "null"
    case JsBoolean(b) => if (b) "true" else "false"
    case JsNumber(d) =>
      if (d == d.toLong.toDouble) d.toLong.toString else d.toString
    case JsString(s) => "\"" + escape(s) + "\""
    case JsArray(xs) => xs.map(stringify).mkString("[", ",", "]")
    case JsObject(fs) =>
      fs.map { case (k, value) => "\"" + escape(k) + "\":" + stringify(value) }.mkString("{", ",", "}")
  }

  def escape(s: String): String = {
    val sb = new StringBuilder(s.length + 8)
    var i  = 0
    while (i < s.length) {
      s.charAt(i) match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c    => sb.append(c)
      }
      i += 1
    }
    sb.toString()
  }

  def parse(input: String): Either[String, JsValue] = {
    val p = new Parser(input)
    try {
      val v = p.parseValue()
      p.skipWs()
      if (!p.eof) Left(s"Trailing characters at offset ${p.pos}")
      else Right(v)
    } catch {
      case e: RuntimeException => Left(e.getMessage)
    }
  }

  private final class Parser(s: String) {
    var pos: Int = 0
    def eof: Boolean = pos >= s.length

    def skipWs(): Unit = {
      while (!eof && s.charAt(pos).isWhitespace) pos += 1
    }

    def parseValue(): JsValue = {
      skipWs()
      if (eof) throw new RuntimeException("Unexpected EOF")
      s.charAt(pos) match {
        case '{' => parseObject()
        case '[' => parseArray()
        case '"' => JsString(parseString())
        case 't' => expect("true"); JsBoolean(true)
        case 'f' => expect("false"); JsBoolean(false)
        case 'n' => expect("null"); JsNull
        case c if c == '-' || c.isDigit => parseNumber()
        case c   => throw new RuntimeException(s"Unexpected char '$c' at $pos")
      }
    }

    private def expect(lit: String): Unit = {
      if (s.startsWith(lit, pos)) pos += lit.length
      else throw new RuntimeException(s"Expected '$lit' at $pos")
    }

    private def parseObject(): JsObject = {
      pos += 1 // '{'
      skipWs()
      val buf = mutable.ListBuffer.empty[(String, JsValue)]
      if (!eof && s.charAt(pos) == '}') {
        pos += 1
        return JsObject(Nil)
      }
      var done = false
      while (!done) {
        skipWs()
        if (eof || s.charAt(pos) != '"') throw new RuntimeException(s"Expected string key at $pos")
        val key = parseString()
        skipWs()
        if (eof || s.charAt(pos) != ':') throw new RuntimeException(s"Expected ':' at $pos")
        pos += 1
        val value = parseValue()
        buf += (key -> value)
        skipWs()
        if (eof) throw new RuntimeException("Unterminated object")
        s.charAt(pos) match {
          case ',' => pos += 1
          case '}' => pos += 1; done = true
          case c   => throw new RuntimeException(s"Expected ',' or '}' at $pos, got '$c'")
        }
      }
      JsObject(buf.toList)
    }

    private def parseArray(): JsArray = {
      pos += 1 // '['
      skipWs()
      val buf = mutable.ListBuffer.empty[JsValue]
      if (!eof && s.charAt(pos) == ']') {
        pos += 1
        return JsArray(Nil)
      }
      var done = false
      while (!done) {
        buf += parseValue()
        skipWs()
        if (eof) throw new RuntimeException("Unterminated array")
        s.charAt(pos) match {
          case ',' => pos += 1
          case ']' => pos += 1; done = true
          case c   => throw new RuntimeException(s"Expected ',' or ']' at $pos, got '$c'")
        }
      }
      JsArray(buf.toList)
    }

    private def parseString(): String = {
      pos += 1 // '"'
      val sb = new StringBuilder
      while (!eof) {
        val c = s.charAt(pos)
        pos += 1
        if (c == '"') return sb.toString()
        else if (c == '\\') {
          if (eof) throw new RuntimeException("Unterminated escape")
          val esc = s.charAt(pos)
          pos += 1
          esc match {
            case '"'  => sb.append('"')
            case '\\' => sb.append('\\')
            case '/'  => sb.append('/')
            case 'b'  => sb.append('\b')
            case 'f'  => sb.append('\f')
            case 'n'  => sb.append('\n')
            case 'r'  => sb.append('\r')
            case 't'  => sb.append('\t')
            case 'u'  =>
              if (pos + 4 > s.length) throw new RuntimeException("Invalid unicode escape")
              val code = Integer.parseInt(s.substring(pos, pos + 4), 16)
              sb.append(code.toChar)
              pos += 4
            case other => throw new RuntimeException(s"Invalid escape '\\$other'")
          }
        } else {
          sb.append(c)
        }
      }
      throw new RuntimeException("Unterminated string")
    }

    private def parseNumber(): JsNumber = {
      val start = pos
      if (s.charAt(pos) == '-') pos += 1
      while (!eof && s.charAt(pos).isDigit) pos += 1
      if (!eof && s.charAt(pos) == '.') {
        pos += 1
        while (!eof && s.charAt(pos).isDigit) pos += 1
      }
      if (!eof && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        pos += 1
        if (!eof && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos += 1
        while (!eof && s.charAt(pos).isDigit) pos += 1
      }
      JsNumber(s.substring(start, pos).toDouble)
    }
  }
}
