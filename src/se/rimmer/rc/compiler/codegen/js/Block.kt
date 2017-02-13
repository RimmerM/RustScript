package se.rimmer.rc.compiler.codegen.js

import se.rimmer.rc.compiler.parser.IntLiteral
import se.rimmer.rc.compiler.resolve.*
import java.math.BigInteger
import java.util.*

val zero = IntLiteral(BigInteger.valueOf(0))

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
 *    to ternary expressions without any additional analysis.
 */
fun genInst(scope: VarScope, stmts: ArrayList<Stmt>, inst: Inst): Expr {
    val expr = when(inst) {
        // TODO: Simulate smaller and larger integer types in JS.
        is WidenInst -> inst.from.use(scope)
        is TruncInst -> inst.from.use(scope)

        is FloatToIntInst -> InfixExpr("|", inst.from.use(scope), IntExpr(0))
        is IntToFloatInst -> inst.from.use(scope)

        // TODO: Unsigned div, cmp, shift emulation
        is AddInst -> InfixExpr("+", inst.lhs.use(scope), inst.rhs.use(scope))
        is SubInst -> {
            if(inst.lhs is Lit && inst.lhs.literal == zero) {
                PrefixExpr("-", inst.rhs.use(scope))
            } else {
                InfixExpr("-", inst.lhs.use(scope), inst.rhs.use(scope))
            }
        }
        is MulInst -> InfixExpr("*", inst.lhs.use(scope), inst.rhs.use(scope))
        is DivInst -> InfixExpr("/", inst.lhs.use(scope), inst.rhs.use(scope))
        is RemInst -> InfixExpr("%", inst.lhs.use(scope), inst.rhs.use(scope))

        is CmpInst -> InfixExpr("===", inst.lhs.use(scope), inst.rhs.use(scope))

        is ShlInst -> InfixExpr("<<", inst.arg.use(scope), inst.amount.use(scope))
        is ShrInst -> InfixExpr(">>", inst.arg.use(scope), inst.amount.use(scope))
        is AndInst -> InfixExpr("&", inst.lhs.use(scope), inst.rhs.use(scope))
        is OrInst -> InfixExpr("|", inst.lhs.use(scope), inst.rhs.use(scope))
        is XorInst -> InfixExpr("^", inst.lhs.use(scope), inst.rhs.use(scope))

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
                ArrayExpr(fields.mapIndexed { i, _ ->
                    val update = inst.updates.find { it.first == i }
                    update?.second?.use(scope) ?: FieldExpr(source, IntExpr(i))
                })
            }
        }
        is RecordInst -> {
            val args = inst.fields.map { it.use(scope) }
            when(inst.con.parent.kind) {
                RecordKind.Enum -> IntExpr(inst.con.index)
                RecordKind.Single -> if(args.size == 1) args[0] else ArrayExpr(args)
                RecordKind.Multi -> {
                    // Special case: when the type contains exactly one empty constructor and one with content,
                    // we use null to indicate the empty constructor.
                    if(isConstructorInline(inst.con.parent)) {
                        if(inst.con.content == null) {
                            NullExpr
                        } else if(args.size == 1) {
                            args[0]
                        } else {
                            ArrayExpr(args)
                        }
                    } else {
                        ArrayExpr(listOf(IntExpr(inst.con.index)) + args)
                    }
                }
            }
        }
        is TupInst -> {
            val args = inst.fields.map { it.use(scope) }
            ArrayExpr(args)
        }
        is ArrayInst -> {
            val args = inst.values.map { it.use(scope) }
            ArrayExpr(args)
        }
        is MapInst -> {
            throw NotImplementedError()
        }
        is FunInst -> {
            val funScope = scope.genChild(null)
            val args = inst.function.args.map { funScope.genVar(it.value.name, it.value.uses.size) }
            FunExpr(null, args, genBlock(funScope, inst.function.body).stmts)
        }
        is CallInst -> {
            throw NotImplementedError()
        }
        is CallDynInst -> {
            val function = inst.function.use(scope)
            val args = inst.args.map { it.use(scope) }
            CallExpr(function, args)
        }
        is CallForeignInst -> {
            throw NotImplementedError()
        }
        is IfInst -> {
            throw NotImplementedError()
        }
        is BrInst -> {
            if(inst.to.incoming.size == 1) {
                stmts.addAll(genBlock(scope, inst.to).stmts)
            } else {
                throw NotImplementedError()
            }
            UndefinedExpr
        }
        is PhiInst -> {
            throw NotImplementedError()
        }
        is RetInst -> {
            stmts.add(ReturnStmt(inst.value.use(scope)))
            UndefinedExpr
        }
        else -> throw NotImplementedError()
    }

    if(inst.uses.size > 1 && inst.type !== PrimTypes.unitType && !shouldAlwaysInline(expr)) {
        val v = scope.genVar(inst.name, inst.uses.size)
        stmts.add(VarDecl(v, expr))
        inst.codegen = VarExpr(v)
    } else if(inst.type === PrimTypes.unitType && expr !is UndefinedExpr) {
        stmts.add(ExprStmt(expr))
        inst.codegen = UndefinedExpr
    } else {
        inst.codegen = expr
    }

    return inst.codegen as Expr
}

/*
 * Checks if the constructor field of this record can be inlined and represented as null.
 */
fun isConstructorInline(type: RecordType) =
    type.constructors.size <= 2 &&
    type.constructors.any { it.content == null }

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
