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

    override fun run(vararg args: String): Int {
        if (args.size > 0) {
            try {
                execute(Expression(args.joinToString(" ")))
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

private fun checkDebug(args: Array<String>): Array<String> {
    if (args.isNotEmpty() && args[0] == "debug") {
        println("*** press enter after starting the debugger ***")
        BufferedReader(InputStreamReader(System.`in`)).readLine()
        return listOf(*args).subList(1, args.size).toTypedArray()
    }
    return args
}

private fun logError(e: Throwable): Int {
    val writer = StringWriter()
    e.printStackTrace(PrintWriter(writer, true))
    val message = writer.toString()
    println(message)
    LOGGER.severe(message)
    return 1
}

private val LOGGER = Logger.getLogger("Main")
