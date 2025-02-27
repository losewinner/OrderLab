package feedback.parser

import feedback.log.entry.{InjectionRecord, LogEntry}

import scala.collection.mutable

object TextParser {
  private val InjectionRecordPattern = raw"(?s)flaky record injection (\d+)(\n.+)?".r

  def recognizeInjection(entry: LogEntry): Option[InjectionRecord] =
    if (entry.classname.equals("TraceAgent") || entry.classname.equals("BaselineAgent")) {
      //       WARN: later require(logEntryBuilder.logType == LogType.INFO)
      entry.msg match {
        case InjectionRecordPattern(injection, _) =>
          Some(InjectionRecord(entry.logLine, entry.showtime, injection.toInt))
        case _ => None
      }
    } else None

  private val numbersRegex = raw"([0-9 ,]*)"
  private val FeedbackPattern = raw"injection allow set: \[$numbersRegex\]".r
  private val LogFeedbackPattern =
    raw"${LogFileParser.datetimeRegex} (\[myid.\d*\] )?- INFO  \[.+\] - $FeedbackPattern\n?".r

  private def parseNumbers(numbers: String): Array[Int] =
    if (numbers.isEmpty) Array.empty[Int]
    else numbers.split(raw", ").map { _.toInt }

  def recognizeFeedbackSet(entry: LogEntry): Option[Array[Int]] = entry.msg match {
    case FeedbackPattern(numbers) => Some(parseNumbers(numbers))
    case _ => None
  }

  private val InjectionRecordFilePattern = raw"InjectionTimeRecord-(\d+).csv".r

  def parseInjectionRecordFileId(text: String): Option[Int] = text match {
    case InjectionRecordFilePattern(id) => Some(id.toInt)
    case _ => None
  }

  def getRecordFile(id: Int): String = raw"InjectionTimeRecord-$id.csv"

  private val CaseDirPattern = """([a-zA-Z0-9]+)_(\d+)""".r

  def parseCaseDirName(text: String): Option[String] = text match {
    case CaseDirPattern(system, number) => Some(s"${system.toLowerCase}-$number")
    case _ => None
  }

  private val LogDirPattern = raw"logs-(\d+)".r

  def parseLogDirId(text: String): Option[Int] = text match {
    case LogDirPattern(id) => Some(id.toInt)
    case _ => None
  }

  def getLogDir(id: Int): String = raw"logs-$id"

  // text ==> ("...\n", "...\n", ...)
  def unfoldWithNewLine(text: String): Array[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    var builder: Option[mutable.StringBuilder] = None
    text foreach { c =>
      builder match {
        case Some(builder) => builder append c
        case None => builder = Some(new mutable.StringBuilder append c)
      }
      if (c == '\n') {
        builder foreach { lines += _.toString }
        builder = None
      }
    }
    builder foreach { lines += _.toString }
    lines.toArray
  }

  // text ==> ("...", "...", ...)
  def unfoldWithoutNewLine(text: String): Array[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    var builder = new mutable.StringBuilder
    text foreach { c =>
      if (c == '\n') {
        lines += builder.toString
        builder = new mutable.StringBuilder
      } else {
        builder append c
      }
    }
    lines += builder.toString
    lines.toArray
  }

  def parseLogSet(text: String): Array[Int] = text match {
    case LogFeedbackPattern(_, _, numbers) => parseNumbers(numbers)
  }

  private val SimpleClassNamePattern1 = raw"[a-zA-Z_0-9\.$$]+\.([a-zA-Z_0-9$$]+)".r
  private val SimpleClassNamePattern2 = raw"([a-zA-Z_0-9$$]+)".r

  def getSimpleClassName(text: String): String = text match {
    case SimpleClassNamePattern1(name) => name
    case SimpleClassNamePattern2(name) => name
  }

  private val LineContentPattern = raw"(?s)([^\n]*)\n".r

  def getLineContent(text: String): Option[String] = text match {
    case LineContentPattern(s) => Some(s)
    case _ =>
      require(text.isEmpty)
      None
  }
}
