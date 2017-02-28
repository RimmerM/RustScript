package se.rimmer.rc.compiler.compiler

import se.rimmer.rc.compiler.parser.ModuleParser
import se.rimmer.rc.compiler.parser.ParserListener
import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.resolve.Module
import se.rimmer.rc.compiler.resolve.ModuleHandler
import se.rimmer.rc.compiler.resolve.resolveModule
import se.rimmer.rc.compiler.parser.Module as ASTModule
import java.io.File

/**
 * Creates a module handler that maps module names to file names.
 * Modules are loaded and parsed lazily based on a set of library directories.
 */
fun newFileLoader(libraryPaths: List<File>, validExtensions: List<String>, context: Context): ModuleHandler {
    val dirs = libraryPaths.map { ModuleDir("", it) }
    dirs.forEach { prepare(it, validExtensions, context.diagnostics) }
    return FileLoader(dirs, context)
}

private fun parseFile(name: List<String>, content: String, listener: ParserListener): ASTModule {
    val q = Qualified(name.last(), name.take(name.size - 1), false)
    val module = ASTModule(q)
    ModuleParser(q, content, listener).parseModule(module)
    return module
}

private fun prepare(dir: ModuleDir, validExtensions: List<String>, diagnostics: Diagnostics) {
    if(!dir.dir.isDirectory) {
        diagnostics.warning("invalid library path: ${dir.dir.absolutePath} must be a directory", null)
        return
    }

    dir.dir.listFiles().forEach {
        if(it.isDirectory) {
            val next = ModuleDir(it.name, it)
            dir.next[it.name.capitalize()] = next
            prepare(next, validExtensions, diagnostics)
        } else if(it.extension in validExtensions) {
            dir.availableModules[it.nameWithoutExtension.capitalize()] = it
        }
    }
}

private class FileLoader(private val dirs: List<ModuleDir>, val context: Context): ModuleHandler {
    override fun findModule(path: List<String>): Module? {
        dirs.forEach { dir ->
            val found = find(path, 0, dir)
            if(found != null) return found
        }
        return null
    }

    private fun find(path: List<String>, i: Int, dir: ModuleDir): Module? {
        if(path.size < i + 1) return null
        if(path.size == i + 1) {
            val name = path.last()
            val module = dir.loadedModules[name]
            if(module != null) return module

            val file = dir.availableModules[name]
            if(file != null) {
                val ast = parseFile(path, file.readText(), context.parserListener)
                val resolved = resolveModule(ast, context.types, this)
                dir.loadedModules[name] = resolved
                return resolved
            }
        } else {
            val subDir = dir.next[path[i]]
            if(subDir != null) return find(path, i + 1, subDir)
        }

        return null
    }
}

private class ModuleDir(val name: String, val dir: File) {
    val loadedModules = HashMap<String, Module>()
    val availableModules = HashMap<String, File>()
    val next = HashMap<String, ModuleDir>()
}