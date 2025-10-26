package com.ionsignal.minecraft.ionnerrus.terra.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.GZIPInputStream;

/**
 * Utility class for handling NBT file operations, like decompression.
 */
public final class NBTUtil {

	private NBTUtil() {
		// Private constructor to prevent instantiation
	}

	/**
	 * Detects if an InputStream is Gzip compressed and wraps it in a GZIPInputStream if so.
	 * This is necessary because Minecraft and related tools often save NBT files in a compressed
	 * format.
	 *
	 * @param is
	 *            The original InputStream.
	 * @return A stream that will provide decompressed data.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public static InputStream detectDecompression(InputStream is) throws IOException {
		PushbackInputStream pbis = new PushbackInputStream(is, 2);
		int signature = (pbis.read() & 0xFF) + (pbis.read() << 8);
		pbis.unread(signature >> 8);
		pbis.unread(signature & 0xFF);
		if (signature == GZIPInputStream.GZIP_MAGIC) {
			return new GZIPInputStream(pbis);
		}
		return pbis;
	}
}