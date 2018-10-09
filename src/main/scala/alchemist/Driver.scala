package alchemist

import scala.collection.JavaConverters._
import scala.util.Random
import java.net.Socket
import java.nio.{ByteOrder, ByteBuffer}
import java.util._
import java.io.{BufferedReader, FileInputStream, InputStream, InputStreamReader, OutputStream, PrintWriter, DataInputStream => JDataInputStream, DataOutputStream => JDataOutputStream}

import scala.io.Source
import org.apache.spark.sql.SparkSession

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

import scala.compat.Platform.EOL
import alchemist._


class Driver {
  
  var address: String = _
  var port: Int = _

  var ID: Short = _

  var driverProc: Process = _


  var sock: Socket = _
  var in: InputStream = _

  val writeMessage = new Message
  val readMessage = new Message

  var workerInfo: Array[WorkerInfo] = Array.empty[WorkerInfo]
  var workerClients: Array[WorkerClient] = Array.empty[WorkerClient]
    
//  val driverSock = listenSock.accept()
//  System.err.println(s"Alchemist.Driver: Accepting connection from Alchemist driver on socket")
  var client: DriverSession = _

  def connect(address: String, port: Int): Boolean = {
    println(s"Connecting to Alchemist at $address:$port")

    val pb = new ProcessBuilder("true")

    driverProc = pb.redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start

    sock = new Socket(address, port)

    in = sock.getInputStream

    client = new DriverSession(in, sock.getOutputStream)

    handshake
  }

  def connect: Boolean = {
    val pb = {
      try {
        val fstream: FileInputStream  = new FileInputStream("connection.info")
        // Get the object of DataInputStream
        val in: JDataInputStream = new JDataInputStream(fstream)
        val br: BufferedReader = new BufferedReader(new InputStreamReader(in))
        address = br.readLine()
        port = Integer.parseInt(br.readLine)

        in.close();         //Close the input stream

        println(s"Connecting to Alchemist at $address:$port")
      }
      catch {
        case e: Exception => println("Got this unknown exception: " + e)
      }

      // dummy process
      new ProcessBuilder("true")
    }

    driverProc = pb.redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start

    sock = new Socket(address, port)

    client = new DriverSession(sock.getInputStream, sock.getOutputStream)

    handshake
  }

  def sendMessage: this.type = {

    val ar = writeMessage.finish()
    Collections.reverse(Arrays.asList(ar))

    writeMessage.print

    sock.getOutputStream.write(ar)
    sock.getOutputStream.flush

    receiveMessage
  }

  def receiveMessage: this.type = {

    val in = sock.getInputStream

    val header: Array[Byte] = Array.fill[Byte](5)(0)
    val packet: Array[Byte] = Array.fill[Byte](8192)(0)

    in.read(header, 0, 5)

    readMessage.reset
    readMessage.addHeader(header)

    var remainingBodyLength: Int = readMessage.readBodyLength()

    while (remainingBodyLength > 0) {
      val length: Int = Array(remainingBodyLength, 8192).min
      in.read(packet, 0, length)
//      for (i <- 0 until length)
//        System.out.println(s"Datatype (length):    ${packet(i)}")
      remainingBodyLength -= length
      readMessage.addPacket(packet, length)
    }

    readMessage.print

    this
  }

//  def handleMessage: this.type = {
//
//    val cc = readMessage.readCommandCode
//
//    cc match {
//      case  0 => wait
//      case  2 => requestID
//      case  3 => clientInfo
//      case  4 => sendTestString
//      case  5 => requestTestString
//      case  6 => requestWorkers
//      case  7 => yieldWorkers
//      case  8 => sendAssignedWorkersInfo
//      case  9 => listAllWorkers
//      case 10 => listActiveWorkers
//      case 11 => listInactiveWorkers
//      case 12 => listAssignedWorkers
//      case 13 => loadLibrary
//      case 14 => runTask
//      case 15 => unloadLibrary
//      case 16 => matrixInfo
//      case 17 => matrixLayout
//      case 18 => matrixBlock
//    }
//
//    this
//  }

  def handshake: Boolean = {

    writeMessage.start("HANDSHAKE")

    writeMessage.writeByte(2)
    writeMessage.writeShort(1234)
    writeMessage.writeString("ABCD")

    sendMessage

    var handshakeSuccess: Boolean = false

    if (readMessage.readCommandCode == 1) {
      if (readMessage.readShort == 4321) {
        if (readMessage.readString == "DCBA") {
          handshakeSuccess = true
        }
      }
    }

    handshakeSuccess
  }

  def requestID: this.type = {

    writeMessage.start("REQUEST_ID")

    sendMessage

    if (readMessage.readCommandCode == 2) {
      ID = readMessage.readShort
    }

    this
  }

  def clientInfo(numWorkers: Short, logDir: String): this.type = {

    writeMessage.start("CLIENT_INFO")
    writeMessage.writeShort(numWorkers)
    writeMessage.writeString(logDir)

    sendMessage

    if (readMessage.readCommandCode == 3) {
//      ID = readMessage.readShort
    }

    this
  }

  def sendTestString(testString: String): String = {

    writeMessage.start("SEND_TEST_STRING")
    writeMessage.writeString(testString)

    sendMessage

    var responseString: String = ""

    if (readMessage.readCommandCode == 4) {
      responseString = readMessage.readString
    }

    responseString
  }

  def requestTestString: String = {

    writeMessage.start("REQUEST_TEST_STRING")

    sendMessage

    var testString: String = ""

    if (readMessage.readCommandCode == 4) {
      testString = readMessage.readString
    }

    testString
  }

  def requestWorkers(numWorkers: Short): this.type = {

    println(s"Requesting $numWorkers Alchemist workers")

    writeMessage.start("REQUEST_WORKERS")
    writeMessage.writeShort(numWorkers)

    sendMessage

    val numAssignedWorkers: Short = readMessage.readShort()

    if (numAssignedWorkers > 0) {
      workerInfo = (0 until numAssignedWorkers).map(_ => new WorkerInfo(readMessage.readShort(), readMessage.readString(), readMessage.readString(), readMessage.readShort())).toArray
    }
    else {
      println(s"Alchemist could not assign $numWorkers workers")
    }

    connectToWorkers
  }

  def yieldWorkers: this.type = {

    println(s"Yielding Alchemist workers")

    writeMessage.start("YIELD_WORKERS")

    sendMessage

    val message: String = readMessage.readString()

    println(message)

    this
  }

  def sendMatrixInfo(numRows: Long, numCols: Long): MatrixHandle = {

    writeMessage.start("MATRIX_INFO")
    writeMessage.writeByte(0)        // Type: dense
    writeMessage.writeByte(0)        // Layout: by rows (default)
    writeMessage.writeLong(numRows)         // Number of rows
    writeMessage.writeLong(numCols)         // Number of columns

    sendMessage

    val matrixID: Short = readMessage.readShort
    val matrixLayout: Array[Short] = extractLayout

    new MatrixHandle(matrixID, numRows, numCols, matrixLayout)
  }

  def extractLayout: Array[Short] = {

    val numRows: Long = readMessage.readLong

    (0l until numRows).map(_ => readMessage.readShort).toArray
  }

  def connectToWorkers: this.type = {

    workerClients = workerInfo.map(w => w.connect())

    this
  }

  def sendAssignedWorkersInfo: this.type = {

    writeMessage.start("SEND_ASSIGNED_WORKERS_INFO")

    sendMessage
  }

  def listAllWorkers: this.type = {

    writeMessage.start("LIST_ALL_WORKERS")

    sendMessage
  }

  def listActiveWorkers: this.type = {

    writeMessage.start("LIST_ACTIVE_WORKERS")

    sendMessage
  }

  def listInactiveWorkers: this.type = {

    writeMessage.start("LIST_INACTIVE_WORKERS")

    sendMessage
  }

  def listAssignedWorkers: this.type = {

    writeMessage.start("LIST_ASSIGNED_WORKERS")

    sendMessage
  }

  def loadLibrary: this.type = {

    writeMessage.start("LOAD_LIBRARY")

    sendMessage
  }

  def runTask: this.type = {

    writeMessage.start("RUN_TASK")

    sendMessage
  }

  def unloadLibrary: this.type = {

    writeMessage.start("UNLOAD_LIBRARY")

    sendMessage
  }

  def matrixInfo: this.type = {

    writeMessage.start("MATRIX_INFO")

    sendMessage
  }

  def matrixLayout: this.type = {

    writeMessage.start("MATRIX_LAYOUT")

    sendMessage
  }

  def matrixBlock: this.type = {

    writeMessage.start("MATRIX_BLOCK")

    sendMessage
  }

  def disconnectFromAlchemist: this.type = {
    println(s"Disconnecting from Alchemist")

    this
  }

  def stop: this.type = {
    yieldWorkers.disconnectFromAlchemist
  }
}