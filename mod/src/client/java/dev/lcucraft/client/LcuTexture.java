package dev.lcucraft.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MipmapHelper;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.EXTTextureCompressionS3TC;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A converted game texture: repeating (the game's UVs tile), trilinear with the full mip chain and anisotropic
 * filtering, so streets and walls stay clean at a distance and at grazing angles.
 *
 * Stored on the GPU block-compressed like the game keeps them (DXT1 without alpha, DXT5 with it): the city's
 * ~1800 textures take ~2.6 GB as plain RGBA, about a quarter of that compressed. The decoded images are freed
 * right after the upload (they live in native memory, outside the Java heap).
 */
public class LcuTexture extends AbstractTexture {
	private static final float MAX_ANISOTROPY = 16f;
	private static Float anisotropy;
	private static Boolean s3tc;

	/** the game's tangent-space normal map for this texture (given to Iris, see IrisCompat), or null */
	public final Path normalMap;

	public LcuTexture(Path png) throws IOException {
		this(png, null);
	}

	public LcuTexture(Path png, Path normalMap) throws IOException {
		this.normalMap = normalMap;
		NativeImage img;
		try (InputStream in = Files.newInputStream(png)) {
			img = NativeImage.read(in);
		}
		// full chain down to 1 px on the short side (v0.1 stopped at 4 levels, far textures shimmered)
		int levels = Integer.numberOfTrailingZeros(Math.min(Integer.highestOneBit(img.getWidth()),
				Integer.highestOneBit(img.getHeight())));
		NativeImage[] mips = MipmapHelper.getMipmapLevelsImages(img, levels);
		try {
			if (compression()) {
				uploadCompressed(mips, levels, img.getFormat() == NativeImage.Format.RGBA);
			} else {
				TextureUtil.prepareImage(getGlId(), levels, img.getWidth(), img.getHeight());
				for (int i = 0; i <= levels; i++) {
					// clamp = false -> GL_REPEAT
					mips[i].upload(i, 0, 0, 0, 0, mips[i].getWidth(), mips[i].getHeight(), true, false, levels > 0, false);
				}
			}
			setFilter(true, levels > 0);
			float a = anisotropy();
			if (a > 1) {
				GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, a);
			}
		} finally {
			for (NativeImage m : mips) {
				m.close();  // mips[0] is img
			}
		}
	}

	/** Every level as DXT1/DXT5: the driver compresses the RGBA pixels while uploading them. */
	private void uploadCompressed(NativeImage[] mips, int levels, boolean alpha) {
		GlStateManager._bindTexture(getGlId());
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, levels);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0f);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
		// Minecraft leaves the unpack state of its own last upload (row length, skipped pixels and rows of a font
		// glyph or atlas sprite): with it the driver would read our tightly packed pixels at the wrong stride, past
		// the end of the buffer, and crash
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
		boolean usesAlpha = alpha && hasAlpha(mips[0]);
		int format = usesAlpha ? EXTTextureCompressionS3TC.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT
				: EXTTextureCompressionS3TC.GL_COMPRESSED_RGB_S3TC_DXT1_EXT;
		for (int i = 0; i <= levels; i++) {
			NativeImage m = mips[i];
			IntBuffer px = MemoryUtil.memAllocInt(m.getWidth() * m.getHeight());
			try {
				px.put(m.makePixelArray()).flip();  // ARGB ints = B, G, R, A bytes in memory
				GL11.glTexImage2D(GL11.GL_TEXTURE_2D, i, format, m.getWidth(), m.getHeight(), 0, GL12.GL_BGRA,
						GL11.GL_UNSIGNED_BYTE, px);
			} finally {
				MemoryUtil.memFree(px);
			}
		}
	}

	/** True if any texel is not fully opaque (cut-outs, glass, decals, glows). */
	private static boolean hasAlpha(NativeImage img) {
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getColor(x, y) >>> 24) != 0xFF) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean compression() {
		if (s3tc == null) {
			s3tc = GL.getCapabilities().GL_EXT_texture_compression_s3tc;
		}
		return s3tc;
	}

	/** Driver maximum, capped at 16x; 1 if the extension is missing. */
	private static float anisotropy() {
		if (anisotropy == null) {
			anisotropy = GL.getCapabilities().GL_EXT_texture_filter_anisotropic
					? Math.min(MAX_ANISOTROPY, GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT))
					: 1f;
		}
		return anisotropy;
	}

	@Override
	public void load(ResourceManager manager) {
	}
}
