package com.magicreg.uriel

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Logger
import javax.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

@QuarkusMain
class Main {
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            Quarkus.run(Application::class.java, *checkDebug(args as Array<String>))
        }
    }
}

@ApplicationScoped
class Application(): QuarkusApplication {

    @ConfigProperty(name = "uriel.service.print.actions")
    val printActions: Boolean = false

    @ConfigProperty(name = "uriel.service.print.script.result")
    val printScriptResult: Boolean = false

    override fun run(vararg args: String): Int {
        addFunction(Action("service", null) { params -> runService(params, printActions) })
        initMusicFunctions()
        if (args.isNotEmpty()) {
            try {
                val result = execute(Expression(args.joinToString(" ")))
                if (printScriptResult && result != null)
                    println(toString(result))
                return 0
            }
            catch (e: Throwable) { return logError(e) }
        }

        val endwords = arrayOf("quit", "exit")
        val input = BufferedReader(InputStreamReader(System.`in`))
        var running = true

        while (running) {
            try {
                print("\n? ")
                var txt = input.readLine()
                if (txt == null)
                    running = false
                else {
                    txt = txt.trim()
                    if (endwords.indexOf(txt) >= 0)
                        running = false
                    else if (txt.isNotEmpty())
                        println("= " + toString(execute(Expression(txt))))
                }
            }
            catch (e: Throwable) { logError(e) }
        }

        return 0
    }
}

private val LOGGER = Logger.getLogger("Main")
private val SERVICE_ACTIONS = arrayOf("validate", "delete", "create", "build", "run", "deploy")
private val SERVICE_ACTIONS_EXECUTED = arrayOf("validated", "deleted", "created", "built", "running", "deployed")

private fun logError(e: Throwable): Int {
    val writer = StringWriter()
    e.printStackTrace(PrintWriter(writer, true))
    val message = writer.toString()
    println(message)
    LOGGER.severe(message)
    return 1
}

private fun validateConfig(service: Service) {
    val missing = service.validate()
    if (missing.isEmpty())
        println("Service configuration is valid")
    else
        println("Missing or empty properties in service configuration: "+missing.joinToString(" "))
}

private fun runService(params: Array<Any?>, printActions: Boolean): Service? {
    val src = if (params.isEmpty()) null else getResource(params[0])
    if (src !is Resource || src.uri == null) {
        LOGGER.info("Not a supported resource type: $src")
        return null
    }
    val service = Service(toMap((src as Resource).getData()))
    for (p in 1 until params.size) {
        val action = getKey(params[p]).toLowerCase()
        val index = SERVICE_ACTIONS.indexOf(action)
        if (index >= 0) {
            if (printActions)
                println("$action service "+service.name+" ...")
            when (action) {
                "validate" -> validateConfig(service)
                "delete" -> service.delete()
                "create" -> service.create()
                "build" -> service.build()
                "run" -> service.run()
                "deploy" -> service.deploy()
                else -> throw RuntimeException("Configuration problem for action $action")
            }
            if (printActions)
                println("service "+service.name+" "+SERVICE_ACTIONS_EXECUTED[index])
        }
        else {
            val msg = "Invalid service action: $action"
            LOGGER.info(msg)
            if (printActions)
                println(msg)
        }
    }
    return service
}

