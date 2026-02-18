package gg.jte.generated.ondemand;
import java.util.List;
public final class JteindexGenerated {
	public static final String JTE_NAME = "index.jte";
	public static final int[] JTE_LINE_INFO = {0,0,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,3,3,3,3,7,7,7,11,11,21,21,23,23,24,24,24,24,27,27,27,36,36,38,38,39,39,39,40,40,40,1,1,1,1};
	private static final gg.jte.runtime.BinaryContent BINARY_CONTENT = gg.jte.runtime.BinaryContent.load(JteindexGenerated.class, "JteindexGenerated.bin", 1,245,39,702,88,39,376,671,20,1,1);
	private static final byte[] TEXT_PART_BINARY_0 = BINARY_CONTENT.get(0);
	private static final byte[] TEXT_PART_BINARY_1 = BINARY_CONTENT.get(1);
	private static final byte[] TEXT_PART_BINARY_2 = BINARY_CONTENT.get(2);
	private static final byte[] TEXT_PART_BINARY_3 = BINARY_CONTENT.get(3);
	private static final byte[] TEXT_PART_BINARY_4 = BINARY_CONTENT.get(4);
	private static final byte[] TEXT_PART_BINARY_5 = BINARY_CONTENT.get(5);
	private static final byte[] TEXT_PART_BINARY_6 = BINARY_CONTENT.get(6);
	private static final byte[] TEXT_PART_BINARY_7 = BINARY_CONTENT.get(7);
	private static final byte[] TEXT_PART_BINARY_8 = BINARY_CONTENT.get(8);
	private static final byte[] TEXT_PART_BINARY_9 = BINARY_CONTENT.get(9);
	private static final byte[] TEXT_PART_BINARY_10 = BINARY_CONTENT.get(10);
	public static void render(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, List<String> sessions) {
		jteOutput.writeBinaryContent(TEXT_PART_BINARY_0);
		gg.jte.generated.ondemand.layout.JtemainGenerated.render(jteOutput, jteHtmlInterceptor, "Dashboard", new gg.jte.html.HtmlContent() {
			public void writeTo(gg.jte.html.HtmlTemplateOutput jteOutput) {
				jteOutput.writeBinaryContent(TEXT_PART_BINARY_1);
				jteOutput.setContext("div", null);
				jteOutput.writeUserContent(sessions.size());
				jteOutput.writeBinaryContent(TEXT_PART_BINARY_2);
				if (sessions.isEmpty()) {
					jteOutput.writeBinaryContent(TEXT_PART_BINARY_3);
				} else {
					jteOutput.writeBinaryContent(TEXT_PART_BINARY_4);
					for (String session : sessions) {
						jteOutput.writeBinaryContent(TEXT_PART_BINARY_5);
						jteOutput.setContext("a", "href");
						jteOutput.writeUserContent(session);
						jteOutput.setContext("a", null);
						jteOutput.writeBinaryContent(TEXT_PART_BINARY_6);
						jteOutput.setContext("span", null);
						jteOutput.writeUserContent(session);
						jteOutput.writeBinaryContent(TEXT_PART_BINARY_7);
					}
					jteOutput.writeBinaryContent(TEXT_PART_BINARY_8);
				}
				jteOutput.writeBinaryContent(TEXT_PART_BINARY_9);
			}
		});
		jteOutput.writeBinaryContent(TEXT_PART_BINARY_10);
	}
	public static void renderMap(gg.jte.html.HtmlTemplateOutput jteOutput, gg.jte.html.HtmlInterceptor jteHtmlInterceptor, java.util.Map<String, Object> params) {
		List<String> sessions = (List<String>)params.get("sessions");
		render(jteOutput, jteHtmlInterceptor, sessions);
	}
}
