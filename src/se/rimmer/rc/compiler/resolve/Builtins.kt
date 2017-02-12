package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.extend



private inline fun Function.prim(type: Type, prim: Block.(Value, Value) -> Value) {
    alwaysInline = true
    val a = defineArg("a", type)
    val b = defineArg("b", type)
    val v = body.prim(a, b)
    body.ret(v)
    returnType = v.type
}

private inline fun Module.prim(name: String, type: Type, prim: Block.(Value, Value) -> Value) {
    val f = defineFun(name)
    f.prim(type, prim)
}

/*
 * We define some basic types and classes here instead of in source, to make handling builtin types easier.
 */
fun preludeModule(): Module {
    val moduleName = Qualified("Prelude", emptyList(), false)
    val module = Module(moduleName)

    val intType = PrimTypes.intType
    val boolType = PrimTypes.boolType

    // Define the basic types.
    module.types["Bool"] = boolType
    module.types["Int"] = intType
    module.types["Double"] = PrimTypes.float(FloatKind.F64)
    module.types["Float"] = PrimTypes.float(FloatKind.F32)
    module.types["String"] = PrimTypes.stringType

    val orderingType = module.defineRecord("Ordering").apply {
        kind = RecordKind.Enum
        module.defineCon(this, "LT", 0, null)
        module.defineCon(this, "EQ", 1, null)
        module.defineCon(this, "GT", 2, null)
    }

    // Define the basic classes.
    val eqClass = module.defineClass("Eq").apply {
        val t = GenType(0)
        defineFun("==", boolType, "a" to t, "b" to t)
        defineFun("!=", boolType, "a" to t, "b" to t)
    }

    module.defineClass("Ord").apply {
        val t = GenType(0)
        t.classes.add(eqClass)
        defineFun("compare", orderingType, "a" to t, "b" to t)
        defineFun("<", boolType, "a" to t, "b" to t)
        defineFun("<=", boolType, "a" to t, "b" to t)
        defineFun(">", boolType, "a" to t, "b" to t)
        defineFun(">=", boolType, "a" to t, "b" to t)
        defineFun("max", t, "a" to t, "b" to t)
        defineFun("min", t, "a" to t, "b" to t)
    }

    module.defineClass("Num").apply {
        val t = GenType(0)
        defineFun("+", t, "a" to t, "b" to t)
        defineFun("-", t, "a" to t, "b" to t)
        defineFun("*", t, "a" to t, "b" to t)
        defineFun("/", t, "a" to t, "b" to t)
        defineFun("abs", t, "it" to t)
        defineFun("signum", t, "it" to t)
    }

    module.prim("rem", intType) { a, b -> rem(null, a, b) }
    module.prim("shl", intType) { a, b -> shl(null, a, b) }
    module.prim("shr", intType) { a, b -> shr(null, a, b) }
    module.prim("and", intType) { a, b -> and(null, a, b) }
    module.prim("or", intType) { a, b -> or(null, a, b) }
    module.prim("xor", intType) { a, b -> xor(null, a, b) }

    return module
}