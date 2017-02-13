package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.IntLiteral
import se.rimmer.rc.compiler.parser.Literal
import se.rimmer.rc.compiler.parser.Qualified
import se.rimmer.rc.compiler.parser.RationalLiteral
import java.math.BigDecimal
import java.math.BigInteger

/*
 * We define some basic types and classes here instead of in source, to make handling builtin types easier.
 */
fun preludeModule(types: Types): Module {
    val moduleName = Qualified("Prelude", emptyList(), false)
    val module = Module(moduleName, types)

    // Define the basic operators.
    module.ops["=="] = Operator(4, false)
    module.ops["/="] = Operator(4, false)
    module.ops["<"] = Operator(4, false)
    module.ops["<="] = Operator(4, false)
    module.ops[">"] = Operator(4, false)
    module.ops[">="] = Operator(4, false)

    module.ops["+"] = Operator(6, false)
    module.ops["-"] = Operator(6, false)
    module.ops["*"] = Operator(7, false)
    module.ops["/"] = Operator(7, false)
    module.ops["rem"] = Operator(7, false)

    module.ops["and"] = Operator(7, false)
    module.ops["or"] = Operator(5, false)
    module.ops["xor"] = Operator(6, false)
    module.ops["shl"] = Operator(8, false)
    module.ops["shr"] = Operator(8, false)

    val intType = PrimTypes.intType
    val boolType = PrimTypes.boolType
    val floatType = PrimTypes.float(FloatKind.F32)
    val doubleType = PrimTypes.float(FloatKind.F64)

    // Define the basic types.
    module.types["Bool"] = boolType
    module.types["Int"] = intType
    module.types["Double"] = doubleType
    module.types["Float"] = floatType
    module.types["String"] = PrimTypes.stringType

    val orderingType = module.defineRecord("Ordering").apply {
        kind = RecordKind.Enum
    }

    val ltCon = module.defineCon(orderingType, "LT", 0, null)
    val eqCon = module.defineCon(orderingType, "EQ", 1, null)
    val gtCon = module.defineCon(orderingType, "GT", 2, null)

    // Define the basic classes with instances for primitive types.
    val eqClass = module.defineClass("Eq").apply {
        val t = GenType(0)
        val eq = defineFun("==", boolType, "a" to t, "b" to t)
        val neq = defineFun("!=", boolType, "a" to t, "b" to t)

        module.defineInstance(this, boolType).apply {
            defineImpl(eq).prim(boolType) { a, b -> cmp(null, Cmp.eq, a, b) }
            defineImpl(neq).prim(boolType) { a, b -> cmp(null, Cmp.neq, a, b) }
        }

        module.defineInstance(this, intType).apply {
            defineImpl(eq).prim(intType) { a, b -> cmp(null, Cmp.eq, a, b) }
            defineImpl(neq).prim(intType) { a, b -> cmp(null, Cmp.neq, a, b) }
        }

        module.defineInstance(this, floatType).apply {
            defineImpl(eq).prim(floatType) { a, b -> cmp(null, Cmp.eq, a, b) }
            defineImpl(neq).prim(floatType) { a, b -> cmp(null, Cmp.neq, a, b) }
        }

        module.defineInstance(this, doubleType).apply {
            defineImpl(eq).prim(doubleType) { a, b -> cmp(null, Cmp.eq, a, b) }
            defineImpl(neq).prim(doubleType) { a, b -> cmp(null, Cmp.neq, a, b) }
        }
    }

    module.defineClass("Ord").apply {
        val t = GenType(0)
        t.classes.add(eqClass)
        val compare = defineFun("compare", orderingType, "a" to t, "b" to t)
        val lt = defineFun("<", boolType, "a" to t, "b" to t)
        val le = defineFun("<=", boolType, "a" to t, "b" to t)
        val gt = defineFun(">", boolType, "a" to t, "b" to t)
        val ge = defineFun(">=", boolType, "a" to t, "b" to t)
        val max = defineFun("max", t, "a" to t, "b" to t)
        val min = defineFun("min", t, "a" to t, "b" to t)

        fun defineOrd(type: Type) {
            module.defineInstance(this, type).apply {
                defineImpl(compare).compare(ltCon, gtCon, eqCon, type)
                defineImpl(lt).prim(type) { a, b -> cmp(null, Cmp.lt, a, b) }
                defineImpl(le).prim(type) { a, b -> cmp(null, Cmp.le, a, b) }
                defineImpl(gt).prim(type) { a, b -> cmp(null, Cmp.gt, a, b) }
                defineImpl(ge).prim(type) { a, b -> cmp(null, Cmp.ge, a, b) }
                defineImpl(max).oneOf(Cmp.gt, type)
                defineImpl(min).oneOf(Cmp.lt, type)
            }
        }

        defineOrd(intType)
        defineOrd(floatType)
        defineOrd(doubleType)
    }

    module.defineClass("Num").apply {
        val t = GenType(0)
        val add = defineFun("+", t, "a" to t, "b" to t)
        val sub = defineFun("-", t, "a" to t, "b" to t)
        val mul = defineFun("*", t, "a" to t, "b" to t)
        val div = defineFun("/", t, "a" to t, "b" to t)
        val abs = defineFun("abs", t, "it" to t)
        val signum = defineFun("signum", t, "it" to t)

        fun defineNum(type: Type, zero: Literal, one: Literal, minusOne: Literal) {
            module.defineInstance(this, type).apply {
                defineImpl(add).prim(type) { a, b -> add(null, a, b) }
                defineImpl(sub).prim(type) { a, b -> sub(null, a, b) }
                defineImpl(mul).prim(type) { a, b -> mul(null, a, b) }
                defineImpl(div).prim(type) { a, b -> div(null, a, b) }

                FunctionBuilder(defineImpl(abs)).run {
                    val a = function.defineArg("a", type)
                    val b = Lit(block, null, type, zero)
                    val neg = function.block()
                    val pos = function.block()

                    block.`if`(block.cmp(null, Cmp.ge, a, b), pos, neg)
                    block = pos
                    block.ret(a)

                    block = neg
                    block.ret(block.sub(null, b, a))
                }

                FunctionBuilder(defineImpl(signum)).run {
                    val a = function.defineArg("a", type)
                    val b = Lit(block, null, type, zero)
                    val neg = function.block()
                    val posz = function.block()
                    val pos = function.block()
                    val z = function.block()

                    block.`if`(block.cmp(null, Cmp.ge, a, b), posz, neg)
                    block = posz
                    block.`if`(block.cmp(null, Cmp.eq, a, b), z, pos)
                    block = pos
                    block.ret(Lit(block, null, type, one))
                    block = z
                    block.ret(b)
                    block = neg
                    block.ret(Lit(block, null, type, minusOne))
                }
            }
        }

        defineNum(intType, IntLiteral(BigInteger.valueOf(0)), IntLiteral(BigInteger.valueOf(1)), IntLiteral(BigInteger.valueOf(-1)))
        defineNum(floatType, RationalLiteral(BigDecimal.valueOf(0)), RationalLiteral(BigDecimal.valueOf(1)), RationalLiteral(BigDecimal.valueOf(-1)))
        defineNum(doubleType, RationalLiteral(BigDecimal.valueOf(0)), RationalLiteral(BigDecimal.valueOf(1)), RationalLiteral(BigDecimal.valueOf(-1)))
    }

    module.prim("rem", intType) { a, b -> rem(null, a, b) }
    module.prim("shl", intType) { a, b -> shl(null, a, b) }
    module.prim("shr", intType) { a, b -> shr(null, a, b) }
    module.prim("and", intType) { a, b -> and(null, a, b) }
    module.prim("or", intType) { a, b -> or(null, a, b) }
    module.prim("xor", intType) { a, b -> xor(null, a, b) }

    return module
}

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

private fun Function.compare(ltCon: Con, gtCon: Con, eqCon: Con, type: Type) = FunctionBuilder(this).run {
    val a = function.defineArg("a", type)
    val b = function.defineArg("b", type)
    val lt = function.block()
    val ge = function.block()
    val gt = function.block()
    val eq = function.block()

    block.`if`(block.cmp(null, Cmp.lt, a, b), lt, ge)
    block = lt
    block.ret(block.record(null, ltCon.parent, ltCon, emptyList()))

    block = ge
    block.`if`(block.cmp(null, Cmp.gt, a, b), gt, eq)

    block = gt
    block.ret(block.record(null, gtCon.parent, gtCon, emptyList()))

    block = eq
    block.ret(block.record(null, eqCon.parent, eqCon, emptyList()))

    returnType = eqCon.parent
}

private fun Function.oneOf(cmp: Cmp, type: Type) = FunctionBuilder(this).run {
    val a = function.defineArg("a", type)
    val b = function.defineArg("b", type)
    val pass = function.block()
    val fail = function.block()

    block.`if`(block.cmp(null, cmp, a, b), pass, fail)
    block = pass
    block.ret(a)

    block = fail
    block.ret(b)
}