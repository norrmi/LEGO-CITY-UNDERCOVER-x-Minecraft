package dev.lcucraft.client.mixin;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets LcuRenderer give a vertex buffer its own index list (shared vertices instead of three per triangle). */
@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
	@Accessor("vertexArrayId")
	int lcucraft$vertexArray();

	@Accessor("indexBufferId")
	int lcucraft$indexBuffer();

	@Accessor("vertexCount")
	void lcucraft$setCount(int count);

	@Accessor("elementFormat")
	void lcucraft$setElementFormat(VertexFormat.IntType format);

	@Accessor("hasNoIndexBuffer")
	void lcucraft$setNoIndexBuffer(boolean none);
}
