package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.resolve.*
import java.util.*

fun genBlock(scope: VarScope, block: Block): BlockStmt {
    val stmts = ArrayList<Stmt>()
    block.instructions.forEach {
        genInst(scope, stmts, it)
    }
    return BlockStmt(stmts)
}

fun Value.use(scope: VarScope): Expr {
    if(codegen == null) {
        throw IllegalStateException("Variable ${scope.base}$${name ?: "reg"} is used before initialization")
    }
    return codegen as Expr
}

/*
 * Generates code for a single instruction while also performing simple optimizations on the generated AST.
 * The optimizations performed here are:
 *  - Inline statements that can be reduced to simple expressions.
 *    This has basically no overhead as all required information is already in the IL,
 *    and greatly improves code size and readability.
 *  - Merge Alloca with Store where it makes sense.
 *  - Inline if-statements. Simple combinations of If, Br, Phi can be inlined
  *   to ternary expressions without any additional analysis.
 */
fun genInst(scope: VarScope, stmts: ArrayList<Stmt>, inst: Inst): Expr {
    val expr = when(inst) {
        is AllocaInst -> {
            inst.uses.firstOrNull()?.let {
                // If the first use is a store within the same block, we let that instruction declare the variable.
                if(it.user is StoreInst && it.user.block === inst.block) {
                    UndefinedExpr
                } else {
                    // Declare the variable and return its lvalue.
                    val v = scope.genVar(inst.name, inst.uses.size)
                    stmts.add(VarStmt(listOf(VarDecl(v, null))))
                    VarExpr(v)
                }
            } ?: UndefinedExpr
        }
        is LoadInst -> inst.value.use(scope)
        is StoreInst -> {
            if(inst.to is AllocaInst && inst.to.codegen == null) {
                stmts.add(VarStmt(listOf(VarDecl(scope.genVar(inst.to.name, inst.to.uses.size), inst.value.use(scope)))))
                UndefinedExpr
            } else {
                AssignExpr("=", inst.to.use(scope), inst.value.use(scope))
            }
        }
        is LoadFieldInst -> FieldExpr(inst.from.use(scope), IntExpr(inst.field))
        is GetFieldInst -> FieldExpr(inst.from.use(scope), IntExpr(inst.field))
        is StoreFieldInst -> AssignExpr("=", FieldExpr(inst.to.use(scope), IntExpr(inst.field)), inst.value.use(scope))
        is UpdateFieldInst -> {
            val fields = (inst.from.type as TupType).fields
            val remaining = fields.size - inst.updates.size
            val from = inst.from.use(scope)

            if(remaining > fields.size / 2) {
                // When most fields remain, we slice the existing value and update the changed values.
                val next = scope.genVar(inst.name, inst.updates.size)
                stmts.add(VarStmt(listOf(VarDecl(next, CallExpr(FieldExpr(from, StringExpr("slice")), emptyList())))))
                inst.updates.forEach {
                    stmts.add(ExprStmt(AssignExpr("=", FieldExpr(VarExpr(next), IntExpr(it.first)), it.second.use(scope))))
                }
                VarExpr(next)
            } else {
                val source = if(remaining <= 1 || from is VarExpr) {
                    from
                } else {
                    val v = scope.genVar(null, remaining)
                    stmts.add(VarStmt(listOf(VarDecl(v, from))))
                    VarExpr(v)
                }

                // When most fields are updated, we create a new array and add the remaining fields.
                ArrayExpr(fields.mapIndexed { i, it ->
                    val update = inst.updates.find { it.first == i }
                    update?.second?.use(scope) ?: FieldExpr(source, IntExpr(i))
                })
            }
        }
        is CastPrimInst -> {
            val source = inst.source.use(scope)
            val target = inst.type as PrimType
            when(target.prim) {
                Primitive.String -> InfixExpr("+", StringExpr(""), source)
                Primitive.Double -> source
                Primitive.Int -> InfixExpr("|", source, IntExpr(0))
                Primitive.Bool -> PrefixExpr("!", PrefixExpr("!", source))
            }
        }
        else -> throw NotImplementedError()
    }

    if(inst.uses.size > 1 && inst.type !is UnitType && !shouldAlwaysInline(expr)) {
        val v = scope.genVar(inst.name, inst.uses.size)
        stmts.add(VarDecl(v, expr))
        inst.codegen = VarExpr(v)
    } else if(inst.type is UnitType && expr !is UndefinedExpr) {
        stmts.add(ExprStmt(expr))
        inst.codegen = UndefinedExpr
    } else {
        inst.codegen = expr
    }

    return inst.codegen as Expr
}

/*
 * Checks if an expression should always be inlined.
 * This should be done if all of the following are true:
 *  - The expression has no side-effects.
 *  - The expression has no runtime overhead if performed multiple times - that is,
 *    the expression would most likely be executed as a register or memory load.
 *  - The code size of the expression is equal to or smaller than declaring and referencing it.
 *  This is currently only an approximation.
 */
fun shouldAlwaysInline(expr: Expr) =
    expr is VarExpr ||
    expr is BoolExpr ||
    expr is NullExpr ||
    (expr is StringExpr && expr.it.length <= 3) ||
    (expr is IntExpr && expr.it <= 99999 && expr.it >= -9999) ||
    (expr is FloatExpr && expr.it <= 9999.0 && expr.it >= -999.0)
