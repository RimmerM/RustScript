package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.*
import se.rimmer.rc.compiler.parser.Expr as ASTExpr
import se.rimmer.rc.compiler.parser.MultiExpr as ASTMultiExpr
import se.rimmer.rc.compiler.parser.LitExpr as ASTLitExpr
import se.rimmer.rc.compiler.parser.AppExpr as ASTAppExpr
import se.rimmer.rc.compiler.parser.FieldExpr as ASTFieldExpr
import se.rimmer.rc.compiler.parser.VarExpr as ASTVarExpr
import se.rimmer.rc.compiler.parser.PrefixExpr as ASTPrefixExpr
import se.rimmer.rc.compiler.parser.InfixExpr as ASTInfixExpr

fun Resolver.resolveExpr(scope: Scope, ast: ASTExpr, resultUsed: Boolean): Expr {
    return when(ast) {
        is ASTMultiExpr -> resolveMulti(scope, ast, resultUsed)
        is ASTLitExpr -> resolveLit(scope, ast)
        is ASTAppExpr -> resolveCall(scope, ast)
        is ASTPrefixExpr -> resolveUnaryCall(scope, Qualified(ast.op, emptyList()), resol)
        else -> throw NotImplementedError()
    }
}

private fun Resolver.resolveMulti(scope: Scope, ast: ASTMultiExpr, resultUsed: Boolean): Expr {
    // Expressions that are part of a statement list are never used, unless they are the last in the list.
    val list = ast.list.mapIndexed { i, expr ->
        resolveExpr(scope, expr, if(i == ast.list.size - 1) resultUsed else false)
    }
    return MultiExpr(list, list.last().type)
}

private fun Resolver.resolveLit(scope: Scope, ast: ASTLitExpr): Expr {
    val type = when(ast.literal) {
        is IntLiteral -> primitiveTypes[Primitive.Int.ordinal]
        is RationalLiteral -> primitiveTypes[Primitive.Double.ordinal]
        is BoolLiteral -> primitiveTypes[Primitive.Bool.ordinal]
        is StringLiteral -> primitiveTypes[Primitive.String.ordinal]
        is CharLiteral -> primitiveTypes[Primitive.Int.ordinal]
        else -> throw NotImplementedError()
    }
    return LitExpr(ast.literal, type)
}

private fun Resolver.resolveCall(scope: Scope, ast: ASTAppExpr): Expr {
    // If the operand is a field expression we need special handling, since there are several options:
    // - the field operand is an actual field of its target and has a function type, which we call.
    // - the field operand is not a field, and we produce a function call with the target as first parameter.
    if(ast.callee is ASTFieldExpr) {

    }

    // Special case for calls with one or two parameters - these can map to builtin operations.
    if(ast.callee is ASTVarExpr) {
        when(ast.args.size) {
            1 -> return resolveUnaryCall(scope, Qualified(ast.callee.name, emptyList()), rv(resolveExpr(scope, ast.callee, true)))
            2 -> return resolveBinaryCall(
                scope, Qualified(ast.callee.name, emptyList()),
                rv(resolveExpr(scope, ast.args[0], true)),
                rv(resolveExpr(scope, ast.args[1], true))
            )
        }
    }

    // Create a list of function arguments.
    
}

private fun Resolver.resolvePrefix(scope: Scope, ast: ASTPrefixExpr): Expr {
    return resolveUnaryCall(scope, Qualified(ast.op, emptyList()), rv(resolveExpr(scope, ast.callee, true)))
}

private fun Resolver.resolveInfix(scope: Scope, ast: ASTInfixExpr): Expr {
    val e = if(ast.ordered) ast else reorder(scope, ast, 0)
    return resolveBinaryCall(
        scope, Qualified(ast.op, emptyList()),
        rv(resolveExpr(scope, ast.lhs, true)),
        rv(resolveExpr(scope, ast.rhs, true))
    )
}

private fun Resolver.resolveUnaryCall(scope: Scope, name: Qualified, arg: Expr): Expr {

}

private fun Resolver.resolveBinaryCall(scope: Scope, name: Qualified, lhs: Expr, rhs: Expr): Expr {

}

private fun Resolver.rv(e: Expr) = if(e.type is LVType) CoerceLVExpr(e, (e.type as LVType).target) else e

val primOps = PrimOp.values().associate { it.sourceName to Operator(it.precedence, false) }

private fun opInfo(scope: Scope, name: Qualified): Operator {
    val op = scope.findOperator(name)
    if(op != null) return op

    if(name.qualifier.isEmpty()) {
        val prim = primOps[name.name]
        if(prim != null) return prim
    }

    return Operator(9, false)
}

private fun reorder(scope: Scope, ast: ASTInfixExpr, min: Int): ASTInfixExpr {
    var lhs = ast
    while(lhs.rhs is ASTInfixExpr && !lhs.ordered) {
        val first = opInfo(scope, Qualified(lhs.op, emptyList()))
        if(first.precedence < min) break

        val rhs = lhs.rhs as ASTInfixExpr
        val second = opInfo(scope, Qualified(rhs.op, emptyList()))
        if(second.precedence > first.precedence || (second.precedence == first.precedence && second.isRight)) {
            lhs.rhs = reorder(scope, rhs, second.precedence)
            if(lhs.rhs == rhs) {
                lhs.ordered = true
                break
            }
        } else {
            lhs.ordered = true
            lhs.rhs = rhs.lhs
            rhs.lhs = lhs
            lhs = rhs
        }
    }
    return lhs
}