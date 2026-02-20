package scala.meta.internal.bsp

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspServers.readInBspConfig
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsProjectDirectories
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import org.scalasbt.ipcsocket.UnixDomainSocket
import org.scalasbt.ipcsocket.Win32NamedPipeSocket

/**
 * Implements BSP server discovery, named "BSP Connection Protocol" in the spec.
 *
 * See https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BspServers(
    mainWorkspace: AbsolutePath,
    charset: Charset,
    client: ConfiguredLanguageClient,
    buildClient: MetalsBuildClient,
    tables: Tables,
    bspGlobalInstallDirectories: List[AbsolutePath],
    config: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    workDoneProgress: WorkDoneProgress,
)(implicit ec: ExecutionContextExecutorService) {
  private def customProjectRoot =
    userConfig().getCustomProjectRoot(mainWorkspace)

  def resolve(): BspResolvedResult = {
    findAvailableServers() match {
      case Nil => ResolvedNone
      case head :: Nil => ResolvedBspOne(head)
      case availableServers =>
        val md5 = digestServerDetails(availableServers)
        val selectedServer = for {
          name <- tables.buildServers.selectedServer()
          server <- availableServers.find(_.getName == name)
        } yield server
        selectedServer match {
          case Some(details) => ResolvedBspOne(details)
          case None => ResolvedMultiple(md5, availableServers)
        }
    }
  }

  def newServer(
      projectDirectory: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      details: BspConnectionDetails,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {

    def newConnection(): Future[SocketConnection] = {
      // For sbt, try direct socket connection to an already-running server first
      if (details.getName() == "sbt") {
        tryDirectSbtConnection(projectDirectory) match {
          case Some(conn) =>
            scribe.info("Connected to sbt server via direct socket connection")
            return Future.successful(conn)
          case None =>
            scribe.info(
              "No running sbt server found, falling back to spawning process"
            )
        }
      }

      val args = details.getArgv.asScala.toList
        /* When running on Windows, the sbt script is passed as an argument to the
         * BSP server. If the script path is encoded using URI encoding the server
         * will fail to start. The workaround is to add `file://`.
         * https://github.com/scalameta/metals/issues/5027
         * and also:
         * https://learn.microsoft.com/en-us/troubleshoot/windows-client/networking/url-encoding-unc-paths-not-url-decoded
         */
        .map { arg =>
          if (
            Properties.isWin && arg.contains("-Dsbt.script=") &&
            !arg.contains("file://") && URIEncoderDecoder.decode(arg) != arg
          )
            arg.replace("-Dsbt.script=", "-Dsbt.script=file://")
          else
            arg
        }

      val variables =
        // With Bazel for example chaning JAVA_HOME might cause Bazel to restart on shell
        if (
          sys.env.contains("JAVA_HOME") && details.getName().contains("bazel")
        ) {
          userConfig().javaHome.zip(sys.env.get("JAVA_HOME")) match {
            case Some((metalsHome, envHome)) if metalsHome != envHome =>
              scribe.warn(
                s"JAVA_HOME set by Metals (${metalsHome}) would be different than the one set in the environment ($envHome), " +
                  "which might cause Bazel to restart on shell, so Metals will not override it."
              )
            case _ =>
          }
          Map.empty[String, String]
        } else JdkSources.envVariables(userConfig().javaHome)

      // Convert credential-related system properties to environment variables
      // This helps BSP servers (like sbt) access custom repository credentials
      val credentialEnvVars = sys.props.collect {
        case (key, value) if key == "coursier.credentials" =>
          "COURSIER_CREDENTIALS" -> value
      }

      val allVariables =
        variables ++ credentialEnvVars + ("SCALA_CLI_POWER" -> "true")

      scribe.info(s"Running BSP server $args")
      val proc = SystemProcess.run(
        args,
        projectDirectory,
        redirectErrorOutput = false,
        allVariables,
        processOut = None,
        processErr = Some(l => scribe.info("BSP server: " + l)),
        discardInput = false,
        threadNamePrefix = s"bsp-${details.getName}",
      )

      val output = new ClosableOutputStream(
        proc.outputStream,
        s"${details.getName} output stream",
      )
      val input = new QuietInputStream(
        proc.inputStream,
        s"${details.getName} input stream",
      )

      val finished = Promise[Unit]()
      proc.complete.ignoreValue.onComplete { res =>
        finished.tryComplete(res)
      }

      Future.successful {
        SocketConnection(
          details.getName(),
          output,
          input,
          List(
            Cancelable(() => proc.cancel)
          ),
          finished,
        )
      }
    }

    BuildServerConnection.fromSockets(
      projectDirectory,
      bspTraceRoot,
      buildClient,
      client,
      newConnection,
      tables.dismissedNotifications.ReconnectBsp,
      tables.dismissedNotifications.RequestTimeout,
      config,
      userConfig(),
      details.getName(),
      bspStatusOpt,
      workDoneProgress = workDoneProgress,
    )
  }

  /**
   * Try to connect directly to an already-running sbt server by reading the
   * portfile and connecting to its socket. This avoids spawning a new JVM
   * process (~10s) and instead connects in ~100ms.
   */
  private def tryDirectSbtConnection(
      projectDirectory: AbsolutePath
  ): Option[SocketConnection] = {
    try {
      // Read .bsp/sbt.json to get the portfile path from the data field
      val bspConfigPath = projectDirectory.resolve(".bsp").resolve("sbt.json")
      if (!Files.exists(bspConfigPath.toNIO)) return None

      val configText = FileIO.slurp(bspConfigPath, charset)
      val configJson = ujson.read(configText)
      val portfilePath = Try(
        configJson("data")("sbtPortfile").str
      ).toOption.getOrElse(return None)

      // Resolve portfile relative to the project directory
      val portfile = new File(projectDirectory.toNIO.toFile, portfilePath)
      if (!portfile.exists()) return None

      // Parse the portfile to get socket URI and token file
      val portfileJson = ujson.read(
        new String(Files.readAllBytes(portfile.toPath), StandardCharsets.UTF_8)
      )
      val serverUri = new URI(portfileJson("uri").str)
      val token = portfileJson.obj
        .get("tokenfilePath")
        .flatMap(_.strOpt)
        .flatMap { tokenFilePath =>
          Try {
            val tokenJson = ujson.read(
              new String(
                Files.readAllBytes(new File(tokenFilePath).toPath),
                StandardCharsets.UTF_8,
              )
            )
            tokenJson("token").str
          }.toOption
        }

      // Connect to the sbt server socket
      val socket: Socket = serverUri.getScheme match {
        case "local" =>
          val path = serverUri.getSchemeSpecificPart
          // Strip leading // from the path if present (URI format: local:///path)
          val cleanPath =
            if (path.startsWith("//")) path.substring(2) else path
          if (Properties.isWin)
            new Win32NamedPipeSocket(s"\\\\.\\pipe\\$cleanPath", false)
          else
            new UnixDomainSocket(cleanPath, false)
        case "tcp" =>
          new Socket(
            InetAddress.getByName(serverUri.getHost),
            serverUri.getPort,
          )
        case scheme =>
          scribe.warn(s"Unsupported sbt server URI scheme: $scheme")
          return None
      }

      val output = new ClosableOutputStream(
        socket.getOutputStream,
        "sbt direct socket output",
      )
      val input = new QuietInputStream(
        socket.getInputStream,
        "sbt direct socket input",
      )

      // The finished promise is completed via JSONRPC error handling when the
      // socket disconnects; the launcher's listener thread detects the broken
      // connection and triggers reconnection through the register() error path.
      val finished = Promise[Unit]()

      Some(
        SocketConnection(
          "sbt",
          output,
          input,
          List(Cancelable(() => socket.close())),
          finished,
          optToken = token,
        )
      )
    } catch {
      case NonFatal(e) =>
        scribe.warn(s"Failed to connect directly to sbt server: $e")
        None
    }
  }

  /**
   * Returns a list of BspConnectionDetails from reading the .bsp/
   *  entries. Notes that this will not return Bloop even though it
   *  may be a server in the current workspace
   */
  def findAvailableServers(): List[BspConnectionDetails] =
    findJsonFiles().flatMap(readInBspConfig(_, charset))

  private def findJsonFiles(): List[AbsolutePath] = {
    val buf = List.newBuilder[AbsolutePath]
    def visit(dir: AbsolutePath): Unit =
      dir.list.foreach { p =>
        if (p.isJson) {
          buf += p
        }
      }
    visit(mainWorkspace.resolve(Directories.bsp))
    customProjectRoot.map(_.resolve(Directories.bsp)).foreach(visit)
    bspGlobalInstallDirectories.foreach(visit)
    buf.result()
  }

  private def digestServerDetails(
      candidates: List[BspConnectionDetails]
  ): String = {
    val md5 = MessageDigest.getInstance("MD5")
    candidates.foreach { details =>
      md5.update(details.getName.getBytes(StandardCharsets.UTF_8))
    }
    MD5.bytesToHex(md5.digest())
  }

}

object BspServers {
  def globalInstallDirectories(implicit
      ec: ExecutionContext
  ): List[AbsolutePath] = {
    val dirs = MetalsProjectDirectories.fromPath("bsp", silent = false)
    dirs match {
      case Some(dirs) =>
        List(dirs.dataLocalDir, dirs.dataDir).distinct
          .filter(MetalsProjectDirectories.isNotBroken)
          .map(path => Try(AbsolutePath(path)).toOption)
          .flatten
      case None =>
        Nil
    }
  }

  def readInBspConfig(
      path: AbsolutePath,
      charset: Charset,
  ): Option[BspConnectionDetails] = {
    val text = FileIO.slurp(path, charset)
    val gson = new Gson()
    Try(gson.fromJson(text, classOf[BspConnectionDetails])).fold(
      e => {
        scribe.error(s"parse error: $path", e)
        None
      },
      details => {
        if (details.getVersion() == null) {
          val json = ujson.read(text)
          json("millVersion").strOpt.map(details.setVersion)
        }
        Some(details)
      },
    )
  }

}
