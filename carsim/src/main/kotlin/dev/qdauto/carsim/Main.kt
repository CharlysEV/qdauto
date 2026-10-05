package dev.qdauto.carsim

import dev.qdauto.carsim.cli.ArgParser
import dev.qdauto.carsim.cli.HelpText
import dev.qdauto.carsim.cli.UsageException
import dev.qdauto.carsim.run.CarRun
import dev.qdauto.carsim.run.RunSettings
import dev.qdauto.carsim.selftest.SelfTest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Console.install()
    val code = runCli(args)
    Console.flush()
    exitProcess(code)
}

/** La CLI completa; devuelve el código de salida sin terminar el proceso (para los tests). */
fun runCli(args: Array<String>): Int {
    val options = try {
        ArgParser.parse(args)
    } catch (e: UsageException) {
        Console.error("Error: ${e.message}")
        Console.error("Usa --help para ver las opciones.")
        return ExitCodes.USAGE
    }
    if (options.help) {
        Console.line(HelpText.text())
        return ExitCodes.OK
    }
    return try {
        val result = if (options.selfTest) SelfTest(options).run() else CarRun(options, RunSettings.forCar(options)).run()
        result.exitCode
    } catch (e: SetupException) {
        Console.error("Error: ${e.message}")
        ExitCodes.SETUP
    } catch (e: Exception) {
        Console.error("Error inesperado: $e\n${e.stackTraceToString().trimEnd()}")
        ExitCodes.SETUP
    }
}
