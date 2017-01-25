package se.rimmer.rc.compiler.parser

import org.junit.Assert
import org.junit.Test
import se.rimmer.rc.compiler.resolve.*

class ResolveTypeTest {
    @Test
    fun primitives() {
        val primitives = Primitive.values().map { ConType(q(it.sourceName)) to PrimType(it) }
        val ast = Module(q("Test"))
        val r = Resolver(ast)

        primitives.forEach {
            val type = r.resolveType(r.module, it.first, null)
            Assert.assertEquals(it.second, type)
        }
    }

    @Test
    fun alias() {
        val ast = Module(q("Test"))
        ast.decls.add(TypeDecl(SimpleType("Boolean", emptyList()), ConType(q("Bool"))))

        val r = Resolver(ast)
        r.resolve()

        Assert.assertEquals(PrimType(Primitive.Bool), r.resolveType(r.module, ConType(q("Boolean")), null))
    }
}