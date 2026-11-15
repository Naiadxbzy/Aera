package com.changyu.ochat;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RawMarkupCodeTest {
 private static final String SVG="<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 700\" role=\"img\" aria-labelledby=\"title desc\"><title>Pelican riding a bicycle</title><desc>A cheerful white pelican</desc><circle cx=\"50\" cy=\"50\" r=\"40\" fill=\"#4aa3df\"/></svg>";
 private String body(String source,MarkdownRenderer.Fence block){return source.substring(block.bodyStart,block.bodyEnd);}
 @Test public void bareSvgIsCodeInsteadOfStrippedTitleAndDescription(){
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(SVG);assertEquals(1,blocks.size());
  assertEquals(SVG,body(SVG,blocks.get(0)));assertEquals("svg",blocks.get(0).language);assertTrue(blocks.get(0).closed);
  String processed=MarkdownRenderer.processMarkdown(SVG);assertTrue(processed.contains(SVG));
  StringBuilder rendered=new StringBuilder();
  org.commonmark.parser.Parser.builder().build().parse(processed).accept(new org.commonmark.node.AbstractVisitor(){@Override public void visit(org.commonmark.node.FencedCodeBlock block){rendered.append(block.getLiteral());}});
  assertEquals(SVG+"\n",rendered.toString());
 }
 @Test public void svgAndSurroundingProseKeepExactBoundaries(){
  String source="前文\n\n"+SVG+"\n\n后文";List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(source);
  assertEquals(1,blocks.size());assertEquals(SVG,body(source,blocks.get(0)));
  assertEquals("前文\n\n",source.substring(0,blocks.get(0).start));assertEquals("\n\n后文",source.substring(blocks.get(0).end));
 }
 @Test public void everyRawStreamingSnapshotRemainsLiteral(){
  for(int end=4;end<=SVG.length();end++){
   String partial=SVG.substring(0,end);List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(partial);
   assertEquals("at "+end,1,blocks.size());assertEquals(partial,body(partial,blocks.get(0)));assertTrue(MarkdownRenderer.processMarkdown(partial).contains(partial));
  }
 }
 @Test public void xmlDeclarationAndDoctypeRemainWithSvg(){
  String source="<?xml version=\"1.0\"?>\n<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n"+SVG;
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(source);assertEquals(1,blocks.size());assertEquals(source,body(source,blocks.get(0)));
  assertEquals("svg",blocks.get(0).language);assertEquals("svg",MarkupSource.language(source,"xml"));
 }
 @Test public void rootInsideFenceNeverCreatesASecondCard(){
  String source="~~~xml\n"+SVG+"\n~~~\n\n"+SVG;List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(source);
  assertEquals(2,blocks.size());assertFalse(blocks.get(0).literalMarkup);assertTrue(blocks.get(1).literalMarkup);
 }
 @Test public void nestedSvgQuotedDelimitersAndCdataDoNotEndTheRootEarly(){
  String raw="<svg data-note=\"</svg> >\"><svg><path/></svg><!-- </svg> --><text><![CDATA[</svg>\n~~~\n]]></text></svg>";
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(raw+"\nend");
  assertEquals(1,blocks.size());assertEquals(raw,body(raw+"\nend",blocks.get(0)));assertTrue(blocks.get(0).closed);
  assertTrue(MarkdownRenderer.processMarkdown(raw).contains(raw));
 }
 @Test public void codeFenceCharactersInsideSvgAreNotMarkdownBlocks(){
  String raw="<svg>\n<text>\n```html\nnot a code block\n```\n</text>\n</svg>";
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(raw);
  assertEquals(1,blocks.size());assertEquals(raw,body(raw,blocks.get(0)));assertTrue(blocks.get(0).literalMarkup);
 }
 @Test public void codeSpanAndCommentExamplesDoNotBecomeGraphics(){
  assertTrue(MarkdownRenderer.codeBlocks("Use `<svg/>` in an example.").isEmpty());
  assertTrue(MarkdownRenderer.codeBlocks("Use `example\n<svg/>\n` here.").isEmpty());
  assertTrue(MarkdownRenderer.codeBlocks("<!--\n<svg/>\n-->").isEmpty());
 }
 @Test public void scriptProseDoesNotHideFollowingSvg(){
  String source="<script>const example=\"<svg/>\";</script>\n"+SVG;
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(source);assertEquals(1,blocks.size());assertEquals(SVG,body(source,blocks.get(0)));
  assertFalse(MarkdownRenderer.processMarkdown(source).contains("const example"));
 }
 @Test public void completeHtmlAndSelfClosingSvgHaveExplicitPreviewTypes(){
  String html="<!DOCTYPE html>\n<html><body>hello</body></html>\nAfter";
  List<MarkdownRenderer.Fence> blocks=MarkdownRenderer.codeBlocks(html);assertEquals(1,blocks.size());assertEquals("html",blocks.get(0).language);assertTrue(blocks.get(0).closed);
  blocks=MarkdownRenderer.codeBlocks("<svg viewBox=\"0 0 10 10\"/>");assertEquals(1,blocks.size());assertTrue(blocks.get(0).closed);
 }
 @Test public void genericCodeLabelsDetectMarkupButJavascriptDoesNot(){
  for(String label:new String[]{"xml","代码","image/svg+xml","html"})assertEquals(label,"svg",MarkupSource.language(SVG,label));
  assertEquals("javascript",MarkupSource.language("const s = `"+SVG+"`;","javascript"));
 }
}
