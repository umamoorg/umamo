package org.umamo.format.jpeg

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validates [JpegReader]'s decode against golden fixtures.  Each fixture is a tiny JPEG encoded by
 * libjpeg (via Pillow) plus the RGBA libjpeg itself decodes it to.  JPEG is lossy, but decoding is
 * deterministic: this decoder implements the same fixed-point IDCT, upsampling filters, and YCbCr
 * conversion as the reference implementation, so the pixels must match exactly.  The fixtures span
 * baseline 4:4:4 / 4:2:0 / 4:2:2 (the triangle upsampling filters), grayscale, restart intervals, and
 * progressive frames — whose default libjpeg scan script exercises spectral selection plus both DC and
 * AC successive approximation (the refinement scans).  Fixtures are embedded base64 so the test is
 * self-contained.
 */
class JpegDecodeTest {
	/**
	 * Decodes an embedded fixture and asserts it matches the embedded libjpeg-golden RGBA.
	 *
	 * @param Int width         Expected width.
	 * @param Int height        Expected height.
	 * @param String jpegBase64 Base64 of the `.jpg` bytes.
	 * @param String rgbaBase64 Base64 of the golden RGBA8888 (top-first, opaque).
	 */
	private fun assertDecodes(width: Int, height: Int, jpegBase64: String, rgbaBase64: String) {
		val jpeg = Base64.decode(jpegBase64)
		val expected = Base64.decode(rgbaBase64)
		val decoded = JpegReader.read(jpeg)
		assertEquals(width, decoded.width, "width")
		assertEquals(height, decoded.height, "height")
		assertContentEquals(expected, decoded.rgba, "JPEG decode must match libjpeg pixel-for-pixel")
	}

	/** baseline, 4:4:4 (no chroma subsampling). */
	@Test
	fun decodesChroma444() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wAARCAAQABADAREAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
					"AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
					"Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
					"8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9KKNYOHuIfh94958F/CH/V/uP0r3KNY/p3h3iL4fePY/Bfwh/wBX+4/S" +
					"vh6NY/zF4d4i+H3j3nwX8If9X+4/Svco1j+neHuIfh94/9k=",
			rgbaBase64 =
				"AAAA/w8AB/8iABD/MQAW/z0AH/9NASj/YQEx/3ABNv+AAD//jwBF/6IATv+yAFj/vwBf/84AZ//iAHD/8QB3/wAPCP8PDw//Iw8Y" +
					"/zIQIf8/Dyf/Tw8x/2IPOf9wEED/gQ9K/48OT/+iDlj/sg9g/8APaf/OD2//4w56//EPf/8AIRH/DSEY/yEhIf8xISv/PSEw/00h" +
					"Ov9hIUP/biJJ/38hU/+OIVj/oSBg/7Ehaf++IXL/ziF6/+Ahgf/vIIj/ADAa/w4wH/8hMSf/MDAw/z0wOf9NMUD/YTFJ/24xUP9+" +
					"MFj/jDBf/6EwaP+xMHD/vjB4/80wgf/hMIr/7zCP/wI/IP8PPyf/Iz8w/zNAN/9AQED/Tz9J/2JAUf9xQFb/gUBi/5BAZ/+iP27/" +
					"sj94/8A/gP/QQIj/4j+Q//E/l/8CTif/D08t/yNPNv8zT0D/P09F/09PT/9jT1j/cU9f/4BQaP+PUG//oU93/7JPfv/AT4f/0E+Q" +
					"/+JOmP/xTp3/AGAw/w5hN/8hYT//MWFJ/z5gT/9NYVj/YWFh/3BhaP9+YnH/jWJ2/6BhgP+xYYj/vWGQ/85hmP/hYKD/8GCm/wBv" +
					"Ov8Pbz//I29I/zNwUf8/cFr/TnBg/2Fwaf9wcHD/fnF6/41xgP+gcIj/sXCS/75wmP/OcKL/4W+q//BvsP8AgUD/DoBF/yKATv8y" +
					"gFj/P4Be/09/Z/9jf3D/cn92/4CAgP+PgIf/ooCQ/7GAlv+9gJ//zYGo/+GBsf/vgLX/AJBK/w+QUP8ij1j/M49g/z+PaP9Qj3D/" +
					"Y456/3KOf/+Aj4j/j4+P/6OPmP+xj6D/v4+n/8+Psf/ij7n/8JDA/wCiU/8Oolj/IKFg/zChaf8+oXL/T6F5/2Gggf9woIj/f6GR" +
					"/42hmP+hoaH/sKCq/72hsP/Nobr/4aHD/+6iyf8AsVn/DbBf/yCwaP8wsXD/PrF4/02wgf9gsIn/b7CO/3+wmv+OsJ//obGn/7Cw" +
					"sP+9sLn/zbHA/+Gxyf/usdD/AcBh/w/AZv8jwG//MsB4/z/AgP9PwIj/ZMCR/3G/l/+Cv6D/j7+n/6O/sP+zwLf/v7+//8+/yf/i" +
					"wNH/8cDW/wHQaP8Qz2//Is92/zLPfv8/z4f/T9CQ/2LPmP9xz53/gs6n/4/Prf+jz7b/ss6//7/Pxf/Pz8//48/Y//HP3/8A4XH/" +
					"DeJ2/yHggP8w4Yf/PuGQ/07imP9g4aD/b+Gm/4DgsP+O4bf/oeG//7DgyP++4M//zeHY/+Hh4f/w4ej/APF5/w7wgP8i8Yn/MfCR" +
					"/z7wmP9O8aL/YfGr/2/wsP+A77r/j++//6PvyP+z8NH/vu/Z/87w4P/h8On/8PDw/w==",
		)
	}

	/** baseline, 4:2:0 (2x2 chroma subsampling; exercises the triangle upsampling filter). */
	@Test
	fun decodesChroma420() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
					"AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
					"Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
					"8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9K958F/CH/V/uP0r2PwX8If9X+4/SvefBfwh/wBX+4/SjB4zbUPDzxD+" +
					"D3z/2Q==",
			rgbaBase64 =
				"AAAA/wkDB/8ZBRD/JwQY/zkCIf9KAij/WwMz/2oDOP95Az//iANF/5gETv+qBFj/uwJf/8oBZ//ZBHD/4wZ3/wYMCP8PDw//HhEa" +
					"/y4SIf8/Dyn/UA4y/2EQO/9wD0L/gBBI/40PTv+eEFj/rxBg/8EOaf/QDm//3xB6/+gTf/8IHBH/Eh8W/yEhIf8xISv/QR4y/1Me" +
					"Ov9jIEP/ch9J/4IgUf+RIFj/oSBg/7IgZ//EHnD/0x94/+Iggf/rIoj/CSwY/xIuH/8hMSf/MDAw/0IuOf9RL0D/ZC9L/3IvUP+B" +
					"Llj/jy9d/6EwaP+yMHD/xC52/9Mtf//iL4r/6zKP/wY9IP8PPyX/H0Iu/y5CN/9AQED/UD9H/2JAUf9wQFb/gEFg/45BZf+gQG7/" +
					"sEF2/8E/f//RP4b/30CQ/+lDl/8FTSf/Dk8t/x1SNv8tUz7/Pk9H/09PT/9hUFj/b1Bf/35RZv+NUW3/nlF1/65Rff/AT4f/0E+P" +
					"/95QmP/nU53/Bl0w/w9gNf8fYj//LmNH/z9fUP9PYFj/YWFh/3BhaP+AYXD/jWJ2/55ifv+vYob/wV+Q/9FgmP/fYaD/6WSm/wZt" +
					"OP8PcD3/H3JI/y9yT/9AcFr/T29g/2Fwaf9wcHD/gHF4/41xf/+ecYb/sHGO/8FumP/Rb6D/4HCo/+lzr/8HfUH/D39H/x+BUP8v" +
					"glj/QH9i/1F+af9jf3H/cH94/4CAgP+PgIf/oYGQ/7CAlv/BfqH/0X6o/+GAs//pgrf/B4xK/xGPUP8fkFj/L5Fg/0GOav9SjnL/" +
					"Y456/3CPgP+Aj4j/j4+P/6GQmP+wkJ//wo2p/9GOsf/hkLv/6pPA/wmdU/8SoFj/IKFh/zChaf9Cn3P/Up97/2Ofg/9yn4r/gaCR" +
					"/4+gmP+hoaH/saCo/8Odsv/Tnrr/4qHD/+ujyf8HrVn/EK9f/x+xav8vsXH/QK96/0+vgf9ir4v/cK+Q/4Cwmv+OsJ//oLGp/7Cw" +
					"sP/Crrn/0a7C/+Gxy//qs9D/Bb5h/w7BZv8dw3H/LMJ6/z7AgP9PwIj/YcGT/27Bl/9+waD/jMGl/5/BsP+uwrf/v7+//8+/yf/e" +
					"wtH/58TY/wXOaP8O0G//HdF4/yzSgP8+0In/T9CQ/1/QmP9u0J//ftGn/43Qrf+d0rb/rtG9/7/Pxf/Pz8//3tHa/+jU3/8I3XH/" +
					"EeB2/x/hgP8v4of/QeCQ/1LgmP9i4KH/cOCo/4Dhrv+P4LX/oOK+/7Dgxv/C3s//0t/W/+Hh4f/q5Oj/Dep5/xfsgP8m74n/Ne6R" +
					"/0bsmP9Y7KL/aO2r/3bssP+G7bj/le29/6buyP+37s//yOvX/9fr4P/n7en/8PDw/w==",
		)
	}

	/** baseline, 4:2:2 (2x1 chroma subsampling). */
	@Test
	fun decodesChroma422() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wAARCAAQABADASEAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
					"AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
					"Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
					"8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9K958F/CH/V/uP0ruwdbY/TvDziL4PePY/Bfwh/1f7j9K958F/CH/V/u" +
					"P0rw8HW2P5j8POIvg94//9k=",
			rgbaBase64 =
				"BwAA/xAAB/8gARD/LgEY/0AAH/9RACj/YgAx/3EAOP+AAD//jwBF/58BTv+xAFj/wgBf/9EAZ//gAHD/6gN3/wcMCP8QDg//IBEY" +
					"/zARIf9ADin/UQ0y/2IPOf9xD0L/gRBI/48OT/+gEFb/sRBg/8IOaf/RDXH/4RB4/+oSf/8GHhH/DyAY/x4iIf8uIyv/PyAw/1Ag" +
					"Ov9hIUP/byFJ/38iUf+OIVj/niJg/68haf/BIHD/0SB6/98hgf/oJIj/Bi0Y/w8wH/8eMif/LTEw/z8vOf9QL0L/YTFJ/3AwUv9+" +
					"MFf/jTBf/54yZv+vMXD/wS94/9Augf/fMYr/6DOQ/wc8IP8RPif/IEEw/zBBOf9BP0D/Uj5J/2JAUf9xP1j/gUBg/5BAZ/+gQG7/" +
					"sUB4/8M+f//SPoj/4UCQ/+pCl/8ITCf/EU4t/yBQNv8wUUD/P05H/1BOUf9hUFj/cU9g/4BQZv+QT2//oFB1/7FPfv/BTof/0U6Q" +
					"/+BQmP/pUp//BV4w/w5hN/8dYz//LWNJ/z5gUP9PYFr/YGJh/25haP9+YnD/jWJ2/55ifv+vYYj/wGCO/9BgmP/eYqD/52Sm/wZt" +
					"OP8Pbz//H3JI/y9yUf8/cFr/T29i/2Bxaf9wcHL/fnF4/49wgP+ecYb/sHGQ/8BvmP/Qb6L/3nGq/+dzsP8JfUD/EX5F/yCBTv8w" +
					"gVj/QH9g/1F+af9hgHD/cn94/4CAfv+Qf4f/oYGO/7GAlv/Bfp//0X6o/+GBsf/pgrf/CYxK/xKOUP8gkFj/MJBi/0GPaP9SjnL/" +
					"Y456/3KOgP+Cj4j/kI6P/6GQlv+xj5//w42n/9ONsf/ij7n/65LA/weeUf8QoFj/H6Jg/y+iaf8/oXL/UKB7/2Chgf9woIr/f6GQ" +
					"/4+gmP+gop//sKGo/8CfsP/QoLr/36LD/+ikyf8Grln/Dq9f/x6yaP8tsnH/P7B4/0+vgf9gsIn/b7CQ/3+xmP+OsJ//nrKn/6+x" +
					"sP/Ar7f/0K/A/9+yyf/ptND/CL1g/xG/Zv8gwm//L8F4/0G/gP9Svor/Y8CR/3G/mP+AwJ7/j7+n/6DBrv+xwbf/wr6//9K+yf/h" +
					"wNH/6sPY/wjMaP8Rz2//H9B2/y/QgP9Bz4f/Us6Q/2LPmP9xzp//gc+n/4/Prf+g0Lb/sM+//8LNxf/Szs//4dDY/+rS3/8G3nH/" +
					"D+B4/x7ifv8u4of/P+CQ/1Dgmv9g4aD/b+Co/3/hrv+O4bf/n+O+/6/hxv/A38//0N/Y/+Di4f/p5Oj/Bu15/xDwgP8f8on/LvGR" +
					"/z/wmP9R76L/YfGr/2/wsP9/8Lj/jvC//5/yyP+w8dH/we7X/9Dv4P/g8en/6fTw/w==",
		)
	}

	/** baseline grayscale (single component). */
	@Test
	fun decodesGrayscale() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/wAALCAAQABABAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/APj/AMF/CH/V/uP0r3nwX8If9X+4/SvY/Bfwh/1f7j9K958F/CH/AFf7j9K//9k=",
			rgbaBase64 =
				"AAAA/wUFBf8MDAz/ERER/xYWFv8cHBz/IyMj/ygoKP8tLS3/MjIy/zk5Of8/Pz//RERE/0lJSf9QUFD/VVVV/woKCv8PDw//FhYW" +
					"/xwcHP8gICD/JiYm/y0tLf8yMjL/ODg4/zw8PP9DQ0P/SUlJ/05OTv9TU1P/Wlpa/19fX/8VFRX/Ghoa/yEhIf8nJyf/Kysr/zEx" +
					"Mf84ODj/PT09/0NDQ/9ISEj/Tk5O/1RUVP9ZWVn/X19f/2VlZf9qamr/Hx8f/yQkJP8rKyv/MDAw/zU1Nf87Ozv/QkJC/0dHR/9M" +
					"TEz/UVFR/1hYWP9eXl7/Y2Nj/2hoaP9vb2//dHR0/ykpKf8uLi7/NTU1/zs7O/9AQED/RUVF/0xMTP9RUVH/V1dX/1xcXP9iYmL/" +
					"aGho/21tbf9zc3P/eXl5/35+fv8zMzP/ODg4/z8/P/9FRUX/SUlJ/09PT/9WVlb/W1tb/2FhYf9mZmb/bGxs/3Jycv93d3f/fX19" +
					"/4ODg/+IiIj/Pj4+/0NDQ/9KSkr/UFBQ/1RUVP9aWlr/YWFh/2ZmZv9sbGz/cXFx/3d3d/99fX3/goKC/4iIiP+Ojo7/k5OT/0hI" +
					"SP9NTU3/VFRU/1paWv9fX1//ZGRk/2tra/9wcHD/dnZ2/3t7e/+BgYH/h4eH/4yMjP+SkpL/mJiY/52dnf9TU1P/V1dX/15eXv9k" +
					"ZGT/aWlp/25ubv91dXX/enp6/4CAgP+FhYX/jIyM/5GRkf+Wlpb/nJyc/6Ojo/+np6f/XV1d/2JiYv9oaGj/bm5u/3Nzc/95eXn/" +
					"f39//4SEhP+Kior/j4+P/5aWlv+bm5v/oKCg/6ampv+tra3/srKy/2hoaP9tbW3/c3Nz/3l5ef9+fn7/hISE/4qKiv+Pj4//lZWV" +
					"/5qamv+hoaH/pqam/6urq/+xsbH/uLi4/729vf9ycnL/dnZ2/319ff+Dg4P/iIiI/42Njf+UlJT/mZmZ/5+fn/+kpKT/q6ur/7Cw" +
					"sP+1tbX/u7u7/8LCwv/Hx8f/fHx8/4GBgf+IiIj/jY2N/5KSkv+YmJj/n5+f/6Ojo/+pqan/rq6u/7W1tf+7u7v/v7+//8XFxf/M" +
					"zMz/0dHR/4aGhv+Li4v/kZGR/5eXl/+cnJz/oqKi/6ioqP+tra3/s7Oz/7i4uP+/v7//xMTE/8nJyf/Pz8//1tbW/9vb2/+RkZH/" +
					"lpaW/5ycnP+ioqL/p6en/62trf+zs7P/uLi4/76+vv/Dw8P/ysrK/8/Pz//U1NT/2tra/+Hh4f/m5ub/m5ub/6CgoP+np6f/rKys" +
					"/7Gxsf+3t7f/vr6+/8LCwv/IyMj/zc3N/9TU1P/a2tr/3t7e/+Tk5P/r6+v/8PDw/w==",
		)
	}

	/** baseline with restart intervals (DRI / RSTn markers). */
	@Test
	fun decodesRestartIntervals() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
					"AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
					"Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
					"8vP09fb3+Pn6/90ABAAB/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9K958F/CH/V/uP0r2PwX8If9X+4/SvefBfwh/wBX+4/SjB4z" +
					"bUPDzxD+D3z/2Q==",
			rgbaBase64 =
				"AAAA/wkDB/8ZBRD/JwQY/zkCIf9KAij/WwMz/2oDOP95Az//iANF/5gETv+qBFj/uwJf/8oBZ//ZBHD/4wZ3/wYMCP8PDw//HhEa" +
					"/y4SIf8/Dyn/UA4y/2EQO/9wD0L/gBBI/40PTv+eEFj/rxBg/8EOaf/QDm//3xB6/+gTf/8IHBH/Eh8W/yEhIf8xISv/QR4y/1Me" +
					"Ov9jIEP/ch9J/4IgUf+RIFj/oSBg/7IgZ//EHnD/0x94/+Iggf/rIoj/CSwY/xIuH/8hMSf/MDAw/0IuOf9RL0D/ZC9L/3IvUP+B" +
					"Llj/jy9d/6EwaP+yMHD/xC52/9Mtf//iL4r/6zKP/wY9IP8PPyX/H0Iu/y5CN/9AQED/UD9H/2JAUf9wQFb/gEFg/45BZf+gQG7/" +
					"sEF2/8E/f//RP4b/30CQ/+lDl/8FTSf/Dk8t/x1SNv8tUz7/Pk9H/09PT/9hUFj/b1Bf/35RZv+NUW3/nlF1/65Rff/AT4f/0E+P" +
					"/95QmP/nU53/Bl0w/w9gNf8fYj//LmNH/z9fUP9PYFj/YWFh/3BhaP+AYXD/jWJ2/55ifv+vYob/wV+Q/9FgmP/fYaD/6WSm/wZt" +
					"OP8PcD3/H3JI/y9yT/9AcFr/T29g/2Fwaf9wcHD/gHF4/41xf/+ecYb/sHGO/8FumP/Rb6D/4HCo/+lzr/8HfUH/D39H/x+BUP8v" +
					"glj/QH9i/1F+af9jf3H/cH94/4CAgP+PgIf/oYGQ/7CAlv/BfqH/0X6o/+GAs//pgrf/B4xK/xGPUP8fkFj/L5Fg/0GOav9SjnL/" +
					"Y456/3CPgP+Aj4j/j4+P/6GQmP+wkJ//wo2p/9GOsf/hkLv/6pPA/wmdU/8SoFj/IKFh/zChaf9Cn3P/Up97/2Ofg/9yn4r/gaCR" +
					"/4+gmP+hoaH/saCo/8Odsv/Tnrr/4qHD/+ujyf8HrVn/EK9f/x+xav8vsXH/QK96/0+vgf9ir4v/cK+Q/4Cwmv+OsJ//oLGp/7Cw" +
					"sP/Crrn/0a7C/+Gxy//qs9D/Bb5h/w7BZv8dw3H/LMJ6/z7AgP9PwIj/YcGT/27Bl/9+waD/jMGl/5/BsP+uwrf/v7+//8+/yf/e" +
					"wtH/58TY/wXOaP8O0G//HdF4/yzSgP8+0In/T9CQ/1/QmP9u0J//ftGn/43Qrf+d0rb/rtG9/7/Pxf/Pz8//3tHa/+jU3/8I3XH/" +
					"EeB2/x/hgP8v4of/QeCQ/1LgmP9i4KH/cOCo/4Dhrv+P4LX/oOK+/7Dgxv/C3s//0t/W/+Hh4f/q5Oj/Dep5/xfsgP8m74n/Ne6R" +
					"/0bsmP9Y7KL/aO2r/3bssP+G7bj/le29/6buyP+37s//yOvX/9fr4P/n7en/8PDw/w==",
		)
	}

	/** progressive, 4:4:4 - spectral selection plus DC and AC successive approximation. */
	@Test
	fun decodesProgressive444() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wgARCAAQABADAREAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAABgf/xAAYAQADAQEAAAAAAAAAAAAAAAAABAcF" +
					"CP/aAAwDAQACEAMQAAABj4PHqcyw+Ynj1P8A/8QAFhAAAwAAAAAAAAAAAAAAAAAAAAQF/9oACAEBAAEFAkpAlIEpAlIP/8QAFxEB" +
					"AAMAAAAAAAAAAAAAAAAABQAhMf/aAAgBAwEBPwE9DLhyOXDkcuHoZc//xAAVEQEBAAAAAAAAAAAAAAAAAAACAP/aAAgBAgEBPwEO" +
					"Dg4O/8QAFhAAAwAAAAAAAAAAAAAAAAAAAAEh/9oACAEBAAY/AlBQUFD/xAAUEAEAAAAAAAAAAAAAAAAAAAAg/9oACAEBAAE/IQqq" +
					"v//aAAwDAQACAAMAAAAQFM//xAAUEQEAAAAAAAAAAAAAAAAAAAAg/9oACAEDAQE/EBgBn//EABQRAQAAAAAAAAAAAAAAAAAAACD/" +
					"2gAIAQIBAT8QAB//xAAVEAEBAAAAAAAAAAAAAAAAAAAA8f/aAAgBAQABPxCCgoKC/9k=",
			rgbaBase64 =
				"AAAA/w8AB/8iABD/MQAW/z0AH/9NASj/YQEx/3ABNv+AAD//jwBF/6IATv+yAFj/vwBf/84AZ//iAHD/8QB3/wAPCP8PDw//Iw8Y" +
					"/zIQIf8/Dyf/Tw8x/2IPOf9wEED/gQ9K/48OT/+iDlj/sg9g/8APaf/OD2//4w56//EPf/8AIRH/DSEY/yEhIf8xISv/PSEw/00h" +
					"Ov9hIUP/biJJ/38hU/+OIVj/oSBg/7Ehaf++IXL/ziF6/+Ahgf/vIIj/ADAa/w4wH/8hMSf/MDAw/z0wOf9NMUD/YTFJ/24xUP9+" +
					"MFj/jDBf/6EwaP+xMHD/vjB4/80wgf/hMIr/7zCP/wI/IP8PPyf/Iz8w/zNAN/9AQED/Tz9J/2JAUf9xQFb/gUBi/5BAZ/+iP27/" +
					"sj94/8A/gP/QQIj/4j+Q//E/l/8CTif/D08t/yNPNv8zT0D/P09F/09PT/9jT1j/cU9f/4BQaP+PUG//oU93/7JPfv/AT4f/0E+Q" +
					"/+JOmP/xTp3/AGAw/w5hN/8hYT//MWFJ/z5gT/9NYVj/YWFh/3BhaP9+YnH/jWJ2/6BhgP+xYYj/vWGQ/85hmP/hYKD/8GCm/wBv" +
					"Ov8Pbz//I29I/zNwUf8/cFr/TnBg/2Fwaf9wcHD/fnF6/41xgP+gcIj/sXCS/75wmP/OcKL/4W+q//BvsP8AgUD/DoBF/yKATv8y" +
					"gFj/P4Be/09/Z/9jf3D/cn92/4CAgP+PgIf/ooCQ/7GAlv+9gJ//zYGo/+GBsf/vgLX/AJBK/w+QUP8ij1j/M49g/z+PaP9Qj3D/" +
					"Y456/3KOf/+Aj4j/j4+P/6OPmP+xj6D/v4+n/8+Psf/ij7n/8JDA/wCiU/8Oolj/IKFg/zChaf8+oXL/T6F5/2Gggf9woIj/f6GR" +
					"/42hmP+hoaH/sKCq/72hsP/Nobr/4aHD/+6iyf8AsVn/DbBf/yCwaP8wsXD/PrF4/02wgf9gsIn/b7CO/3+wmv+OsJ//obGn/7Cw" +
					"sP+9sLn/zbHA/+Gxyf/usdD/AcBh/w/AZv8jwG//MsB4/z/AgP9PwIj/ZMCR/3G/l/+Cv6D/j7+n/6O/sP+zwLf/v7+//8+/yf/i" +
					"wNH/8cDW/wHQaP8Qz2//Is92/zLPfv8/z4f/T9CQ/2LPmP9xz53/gs6n/4/Prf+jz7b/ss6//7/Pxf/Pz8//48/Y//HP3/8A4XH/" +
					"DeJ2/yHggP8w4Yf/PuGQ/07imP9g4aD/b+Gm/4DgsP+O4bf/oeG//7DgyP++4M//zeHY/+Hh4f/w4ej/APF5/w7wgP8i8Yn/MfCR" +
					"/z7wmP9O8aL/YfGr/2/wsP+A77r/j++//6PvyP+z8NH/vu/Z/87w4P/h8On/8PDw/w==",
		)
	}

	/** progressive, 4:2:0 - progressive scans over subsampled chroma. */
	@Test
	fun decodesProgressive420() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wgARCAAQABADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAABgf/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/9oA" +
					"DAMBAAIQAxAAAAGPvGTw/8QAFhAAAwAAAAAAAAAAAAAAAAAAAAQF/9oACAEBAAEFAkpAlIEpAlIP/8QAFxEBAAMAAAAAAAAAAAAA" +
					"AAAABgAhMf/aAAgBAwEBPwE8hy5//8QAFREBAQAAAAAAAAAAAAAAAAAAAwD/2gAIAQIBAT8BFr//xAAWEAADAAAAAAAAAAAAAAAA" +
					"AAAAASH/2gAIAQEABj8CUFBQUP/EABQQAQAAAAAAAAAAAAAAAAAAACD/2gAIAQEAAT8hCqq//9oADAMBAAIAAwAAABBT/8QAFBEB" +
					"AAAAAAAAAAAAAAAAAAAAAP/aAAgBAwEBPxB//8QAFhEAAwAAAAAAAAAAAAAAAAAAACEx/9oACAECAQE/EIM//8QAFRABAQAAAAAA" +
					"AAAAAAAAAAAAAPH/2gAIAQEAAT8QgoKCgv/Z",
			rgbaBase64 =
				"AAAA/wkDB/8ZBRD/JwQY/zkCIf9KAij/WwMz/2oDOP95Az//iANF/5gETv+qBFj/uwJf/8oBZ//ZBHD/4wZ3/wYMCP8PDw//HhEa" +
					"/y4SIf8/Dyn/UA4y/2EQO/9wD0L/gBBI/40PTv+eEFj/rxBg/8EOaf/QDm//3xB6/+gTf/8IHBH/Eh8W/yEhIf8xISv/QR4y/1Me" +
					"Ov9jIEP/ch9J/4IgUf+RIFj/oSBg/7IgZ//EHnD/0x94/+Iggf/rIoj/CSwY/xIuH/8hMSf/MDAw/0IuOf9RL0D/ZC9L/3IvUP+B" +
					"Llj/jy9d/6EwaP+yMHD/xC52/9Mtf//iL4r/6zKP/wY9IP8PPyX/H0Iu/y5CN/9AQED/UD9H/2JAUf9wQFb/gEFg/45BZf+gQG7/" +
					"sEF2/8E/f//RP4b/30CQ/+lDl/8FTSf/Dk8t/x1SNv8tUz7/Pk9H/09PT/9hUFj/b1Bf/35RZv+NUW3/nlF1/65Rff/AT4f/0E+P" +
					"/95QmP/nU53/Bl0w/w9gNf8fYj//LmNH/z9fUP9PYFj/YWFh/3BhaP+AYXD/jWJ2/55ifv+vYob/wV+Q/9FgmP/fYaD/6WSm/wZt" +
					"OP8PcD3/H3JI/y9yT/9AcFr/T29g/2Fwaf9wcHD/gHF4/41xf/+ecYb/sHGO/8FumP/Rb6D/4HCo/+lzr/8HfUH/D39H/x+BUP8v" +
					"glj/QH9i/1F+af9jf3H/cH94/4CAgP+PgIf/oYGQ/7CAlv/BfqH/0X6o/+GAs//pgrf/B4xK/xGPUP8fkFj/L5Fg/0GOav9SjnL/" +
					"Y456/3CPgP+Aj4j/j4+P/6GQmP+wkJ//wo2p/9GOsf/hkLv/6pPA/wmdU/8SoFj/IKFh/zChaf9Cn3P/Up97/2Ofg/9yn4r/gaCR" +
					"/4+gmP+hoaH/saCo/8Odsv/Tnrr/4qHD/+ujyf8HrVn/EK9f/x+xav8vsXH/QK96/0+vgf9ir4v/cK+Q/4Cwmv+OsJ//oLGp/7Cw" +
					"sP/Crrn/0a7C/+Gxy//qs9D/Bb5h/w7BZv8dw3H/LMJ6/z7AgP9PwIj/YcGT/27Bl/9+waD/jMGl/5/BsP+uwrf/v7+//8+/yf/e" +
					"wtH/58TY/wXOaP8O0G//HdF4/yzSgP8+0In/T9CQ/1/QmP9u0J//ftGn/43Qrf+d0rb/rtG9/7/Pxf/Pz8//3tHa/+jU3/8I3XH/" +
					"EeB2/x/hgP8v4of/QeCQ/1LgmP9i4KH/cOCo/4Dhrv+P4LX/oOK+/7Dgxv/C3s//0t/W/+Hh4f/q5Oj/Dep5/xfsgP8m74n/Ne6R" +
					"/0bsmP9Y7KL/aO2r/3bssP+G7bj/le29/6buyP+37s//yOvX/9fr4P/n7en/8PDw/w==",
		)
	}

	/** progressive grayscale (single component, 6-scan script). */
	@Test
	fun decodesProgressiveGrayscale() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/wgALCAAQABABAREA/8QAFQABAQAAAAAAAAAAAAAAAAAABgf/2gAIAQEAAAABj7xk8//EABYQAAMAAAAA" +
					"AAAAAAAAAAAAAAAEBf/aAAgBAQABBQJKQJSBKQJSD//EABYQAAMAAAAAAAAAAAAAAAAAAAABIf/aAAgBAQAGPwJQUFBQ/8QAFBAB" +
					"AAAAAAAAAAAAAAAAAAAAIP/aAAgBAQABPyEKqr//2gAIAQEAAAAQX//EABUQAQEAAAAAAAAAAAAAAAAAAADx/9oACAEBAAE/EIKC" +
					"goL/2Q==",
			rgbaBase64 =
				"AAAA/wUFBf8MDAz/ERER/xYWFv8cHBz/IyMj/ygoKP8tLS3/MjIy/zk5Of8/Pz//RERE/0lJSf9QUFD/VVVV/woKCv8PDw//FhYW" +
					"/xwcHP8gICD/JiYm/y0tLf8yMjL/ODg4/zw8PP9DQ0P/SUlJ/05OTv9TU1P/Wlpa/19fX/8VFRX/Ghoa/yEhIf8nJyf/Kysr/zEx" +
					"Mf84ODj/PT09/0NDQ/9ISEj/Tk5O/1RUVP9ZWVn/X19f/2VlZf9qamr/Hx8f/yQkJP8rKyv/MDAw/zU1Nf87Ozv/QkJC/0dHR/9M" +
					"TEz/UVFR/1hYWP9eXl7/Y2Nj/2hoaP9vb2//dHR0/ykpKf8uLi7/NTU1/zs7O/9AQED/RUVF/0xMTP9RUVH/V1dX/1xcXP9iYmL/" +
					"aGho/21tbf9zc3P/eXl5/35+fv8zMzP/ODg4/z8/P/9FRUX/SUlJ/09PT/9WVlb/W1tb/2FhYf9mZmb/bGxs/3Jycv93d3f/fX19" +
					"/4ODg/+IiIj/Pj4+/0NDQ/9KSkr/UFBQ/1RUVP9aWlr/YWFh/2ZmZv9sbGz/cXFx/3d3d/99fX3/goKC/4iIiP+Ojo7/k5OT/0hI" +
					"SP9NTU3/VFRU/1paWv9fX1//ZGRk/2tra/9wcHD/dnZ2/3t7e/+BgYH/h4eH/4yMjP+SkpL/mJiY/52dnf9TU1P/V1dX/15eXv9k" +
					"ZGT/aWlp/25ubv91dXX/enp6/4CAgP+FhYX/jIyM/5GRkf+Wlpb/nJyc/6Ojo/+np6f/XV1d/2JiYv9oaGj/bm5u/3Nzc/95eXn/" +
					"f39//4SEhP+Kior/j4+P/5aWlv+bm5v/oKCg/6ampv+tra3/srKy/2hoaP9tbW3/c3Nz/3l5ef9+fn7/hISE/4qKiv+Pj4//lZWV" +
					"/5qamv+hoaH/pqam/6urq/+xsbH/uLi4/729vf9ycnL/dnZ2/319ff+Dg4P/iIiI/42Njf+UlJT/mZmZ/5+fn/+kpKT/q6ur/7Cw" +
					"sP+1tbX/u7u7/8LCwv/Hx8f/fHx8/4GBgf+IiIj/jY2N/5KSkv+YmJj/n5+f/6Ojo/+pqan/rq6u/7W1tf+7u7v/v7+//8XFxf/M" +
					"zMz/0dHR/4aGhv+Li4v/kZGR/5eXl/+cnJz/oqKi/6ioqP+tra3/s7Oz/7i4uP+/v7//xMTE/8nJyf/Pz8//1tbW/9vb2/+RkZH/" +
					"lpaW/5ycnP+ioqL/p6en/62trf+zs7P/uLi4/76+vv/Dw8P/ysrK/8/Pz//U1NT/2tra/+Hh4f/m5ub/m5ub/6CgoP+np6f/rKys" +
					"/7Gxsf+3t7f/vr6+/8LCwv/IyMj/zc3N/9TU1P/a2tr/3t7e/+Tk5P/r6+v/8PDw/w==",
		)
	}

	/** progressive with restart intervals (EOB runs must reset per interval). */
	@Test
	fun decodesProgressiveRestart() {
		assertDecodes(
			width = 16,
			height = 16,
			jpegBase64 =
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/wgARCAAQABADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAABgf/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/90A" +
					"BAAB/9oADAMBAAIQAxAAAAGPvGTw/8QAFhAAAwAAAAAAAAAAAAAAAAAAAAQF/9oACAEBAAEFAkpB/9BKQf/RSkH/0kpB/8QAFxEB" +
					"AAMAAAAAAAAAAAAAAAAABgAhMf/aAAgBAwEBPwE8hy5//8QAFREBAQAAAAAAAAAAAAAAAAAAAwD/2gAIAQIBAT8BFr//xAAWEAAD" +
					"AAAAAAAAAAAAAAAAAAAAASH/2gAIAQEABj8CUP/QUP/RUP/SUP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8hL//QL//R" +
					"L//SL//aAAwDAQACAAMAAAAQU//EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQMBAT8Qf//EABYRAAMAAAAAAAAAAAAAAAAAAAAh" +
					"Mf/aAAgBAgEBPxCDP//EABUQAQEAAAAAAAAAAAAAAAAAAADx/9oACAEBAAE/EIL/0IL/0YL/0oL/2Q==",
			rgbaBase64 =
				"AAAA/wkDB/8ZBRD/JwQY/zkCIf9KAij/WwMz/2oDOP95Az//iANF/5gETv+qBFj/uwJf/8oBZ//ZBHD/4wZ3/wYMCP8PDw//HhEa" +
					"/y4SIf8/Dyn/UA4y/2EQO/9wD0L/gBBI/40PTv+eEFj/rxBg/8EOaf/QDm//3xB6/+gTf/8IHBH/Eh8W/yEhIf8xISv/QR4y/1Me" +
					"Ov9jIEP/ch9J/4IgUf+RIFj/oSBg/7IgZ//EHnD/0x94/+Iggf/rIoj/CSwY/xIuH/8hMSf/MDAw/0IuOf9RL0D/ZC9L/3IvUP+B" +
					"Llj/jy9d/6EwaP+yMHD/xC52/9Mtf//iL4r/6zKP/wY9IP8PPyX/H0Iu/y5CN/9AQED/UD9H/2JAUf9wQFb/gEFg/45BZf+gQG7/" +
					"sEF2/8E/f//RP4b/30CQ/+lDl/8FTSf/Dk8t/x1SNv8tUz7/Pk9H/09PT/9hUFj/b1Bf/35RZv+NUW3/nlF1/65Rff/AT4f/0E+P" +
					"/95QmP/nU53/Bl0w/w9gNf8fYj//LmNH/z9fUP9PYFj/YWFh/3BhaP+AYXD/jWJ2/55ifv+vYob/wV+Q/9FgmP/fYaD/6WSm/wZt" +
					"OP8PcD3/H3JI/y9yT/9AcFr/T29g/2Fwaf9wcHD/gHF4/41xf/+ecYb/sHGO/8FumP/Rb6D/4HCo/+lzr/8HfUH/D39H/x+BUP8v" +
					"glj/QH9i/1F+af9jf3H/cH94/4CAgP+PgIf/oYGQ/7CAlv/BfqH/0X6o/+GAs//pgrf/B4xK/xGPUP8fkFj/L5Fg/0GOav9SjnL/" +
					"Y456/3CPgP+Aj4j/j4+P/6GQmP+wkJ//wo2p/9GOsf/hkLv/6pPA/wmdU/8SoFj/IKFh/zChaf9Cn3P/Up97/2Ofg/9yn4r/gaCR" +
					"/4+gmP+hoaH/saCo/8Odsv/Tnrr/4qHD/+ujyf8HrVn/EK9f/x+xav8vsXH/QK96/0+vgf9ir4v/cK+Q/4Cwmv+OsJ//oLGp/7Cw" +
					"sP/Crrn/0a7C/+Gxy//qs9D/Bb5h/w7BZv8dw3H/LMJ6/z7AgP9PwIj/YcGT/27Bl/9+waD/jMGl/5/BsP+uwrf/v7+//8+/yf/e" +
					"wtH/58TY/wXOaP8O0G//HdF4/yzSgP8+0In/T9CQ/1/QmP9u0J//ftGn/43Qrf+d0rb/rtG9/7/Pxf/Pz8//3tHa/+jU3/8I3XH/" +
					"EeB2/x/hgP8v4of/QeCQ/1LgmP9i4KH/cOCo/4Dhrv+P4LX/oOK+/7Dgxv/C3s//0t/W/+Hh4f/q5Oj/Dep5/xfsgP8m74n/Ne6R" +
					"/0bsmP9Y7KL/aO2r/3bssP+G7bj/le29/6buyP+37s//yOvX/9fr4P/n7en/8PDw/w==",
		)
	}

	/**
	 * Arithmetic-coded JPEG is out of scope and must be refused by name rather than decoded to garbage.
	 * The fixture is a baseline JPEG whose SOF0 marker is patched to SOF9, which is how a decoder sees
	 * an arithmetic frame.
	 */
	@Test
	fun rejectsArithmeticCoded() {
		val arithmetic =
			Base64.decode(
				"/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMU" +
					"FRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
					"FBQUFBQUFBT/yQARCAAQABADAREAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF" +
					"BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW" +
					"V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi" +
					"4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC" +
					"AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm" +
					"Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq" +
					"8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4/wDBfwh/1f7j9KKNYOHuIfh94958F/CH/V/uP0r3KNY/p3h3iL4fePY/Bfwh/wBX+4/S" +
					"vh6NY/zF4d4i+H3j3nwX8If9X+4/Svco1j+neHuIfh94/9k=",
			)
		assertTrue(JpegReader.matches(arithmetic), "an arithmetic-coded JPEG still carries the JPEG magic")
		val failure = assertFailsWith<IllegalArgumentException> { JpegReader.read(arithmetic) }
		assertTrue(failure.message!!.contains("arithmetic"), "the rejection names the unsupported feature: ${failure.message}")
	}

	/** A non-JPEG buffer is refused rather than misparsed. */
	@Test
	fun rejectsNonJpeg() {
		assertFailsWith<IllegalArgumentException> { JpegReader.read(ByteArray(64)) }
	}
}
