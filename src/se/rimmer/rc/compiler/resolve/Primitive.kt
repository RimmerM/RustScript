package se.rimmer.rc.compiler.resolve

enum class PrimOp(val sourceName: String, val precedence: Int, val args: Int) {
    // Binary
    Add("+", 11, 2), Sub("-", 11, 2), Mul("*", 12, 2), Div("/", 12, 2), Rem("mod", 12, 2),
    Shl("shl", 10, 2), Shr("shr", 10, 2), And("and", 7, 2), Or("or", 5, 2), Xor("xor", 6, 2),
    CmpEq("==", 8, 2), CmpNeq("!=", 8, 2), CmpGt(">", 9, 2), CmpGe(">=", 9, 2), CmpLt("<", 9, 2), CmpLe("<=", 9, 2),

    // Unary
    Neg("-", 0, 1), Not("!", 0, 1)
}

fun PrimOp.isBinary() = !isUnary()
fun PrimOp.isUnary() = this === PrimOp.Neg || this === PrimOp.Not
fun PrimOp.isAndOr() = this === PrimOp.And || this === PrimOp.Or

val primOps = PrimOp.values().associate { it.sourceName to (Operator(it.precedence, false) to it) }
val primUnaryOps = primOps.filterValues { it.second.args == 1 }
val primBinaryOps = primOps.filterValues { it.second.args == 2 }

fun resolvePrimitiveUnaryOp(op: PrimOp, arg: Expr) =
    PrimOpExpr(op, listOf(arg), unaryOpType(op, arg.type))

fun resolvePrimitiveBinaryOp(op: PrimOp, lhs: Expr, rhs: Expr) =
    PrimOpExpr(op, listOf(lhs, rhs), binaryOpType(op, lhs.type, rhs.type))

fun unaryOpType(op: PrimOp, type: Type) = when(op) {
    PrimOp.Neg -> {
        if(type is PrimType && (type.prim == Primitive.Int || type.prim == Primitive.Double)) {
            type
        } else {
            throw ResolveError("cannot negate this type")
        }
    }
    PrimOp.Not -> {
        if(type is PrimType && type.prim == Primitive.Bool) {
            type
        } else {
            throw ResolveError("not can only be applied to booleans")
        }
    }
    else -> throw NotImplementedError()
}

fun binaryOpType(op: PrimOp, lhs: Type, rhs: Type): Type {
    if(lhs !is PrimType || rhs !is PrimType) throw IllegalArgumentException("both types must be primitives")
    if(lhs.prim != rhs.prim) throw ResolveError("primitive operators must be applied to the same types")

    when(op) {
        PrimOp.Add, PrimOp.Sub, PrimOp.Mul, PrimOp.Rem, PrimOp.Div -> {
            when(lhs.prim) {
                Primitive.Int, Primitive.Double -> return lhs
                else -> throw ResolveError("this operator cannot be applied to this type")
            }
        }
        PrimOp.And, PrimOp.Shl, PrimOp.Shr, PrimOp.Or, PrimOp.Xor -> {
            if(lhs.prim == Primitive.Int || lhs.prim == Primitive.Bool) {
                return lhs
            } else {
                throw ResolveError("bitwise operators must be applied to integers or booleans")
            }
        }
        PrimOp.CmpEq, PrimOp.CmpGe, PrimOp.CmpGt, PrimOp.CmpLe, PrimOp.CmpLt, PrimOp.CmpNeq -> {
            when(lhs.prim) {
                Primitive.Int, Primitive.Double -> return primitiveTypes[Primitive.Bool.ordinal]
                else -> throw ResolveError("only integers and booleans can be compared")
            }
        }
        else -> throw NotImplementedError()
    }
}