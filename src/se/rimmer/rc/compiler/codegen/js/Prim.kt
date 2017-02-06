package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.resolve.PrimOp
import se.rimmer.rc.compiler.resolve.Value

/*
 * Generates a builtin operation.
 * Most of these map to JS builtin operators.
 */
fun genPrimitive(scope: VarScope, op: PrimOp, args: List<Value>): Expr {
    primInfix[op]?.let { return it(scope, args) }
    primPrefix[op]?.let { return it(scope, args) }
    throw NotImplementedError()
}

private fun makeInfix(op: String) = {
    scope: VarScope, args: List<Value> -> InfixExpr(op, args[0].use(scope), args[1].use(scope))
}

private fun makePrefix(op: String) = {
    scope: VarScope, args: List<Value> -> PrefixExpr(op, args[0].use(scope))
}

private val primInfix = mapOf(
    PrimOp.Add to makeInfix("+"),
    PrimOp.Sub to makeInfix("-"),
    PrimOp.Mul to makeInfix("*"),
    PrimOp.Div to makeInfix("/"),
    PrimOp.Rem to makeInfix("%"),
    PrimOp.Shl to makeInfix("<<"),
    PrimOp.Shr to makeInfix(">>"),
    PrimOp.And to makeInfix("&"),
    PrimOp.Or to makeInfix("|"),
    PrimOp.Xor to makeInfix("^"),
    PrimOp.CmpEq to makeInfix("==="),
    PrimOp.CmpNeq to makeInfix("!=="),
    PrimOp.CmpGt to makeInfix(">"),
    PrimOp.CmpGe to makeInfix(">="),
    PrimOp.CmpLt to makeInfix("<"),
    PrimOp.CmpLe to makeInfix("<=")
)

private val primPrefix = mapOf(
    PrimOp.Neg to makePrefix("-"),
    PrimOp.Not to makePrefix("!")
)