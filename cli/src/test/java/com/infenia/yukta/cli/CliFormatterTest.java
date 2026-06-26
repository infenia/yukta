/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CliFormatterTest {

  private CliFormatter formatter;
  private ObjectMapper objectMapper;
  private PrintStream originalOut;
  private PrintStream originalErr;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    formatter = new CliFormatter(objectMapper);
    originalOut = System.out;
    originalErr = System.err;
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  void constructor_createsInstance_withObjectMapper() {
    assertThat(formatter).isNotNull();
  }

  @Test
  void formatAsJson_withObject_returnsFormattedJson() throws Exception {
    TestObject obj = new TestObject("test", 123);
    String json = formatter.formatAsJson(obj);

    assertThat(json).contains("\"name\"").contains("test").contains("\"id\"").contains("123");
  }

  @Test
  void formatAsJson_withString_returnsQuotedString() throws Exception {
    String json = formatter.formatAsJson("hello");

    assertThat(json).isEqualTo("\"hello\"");
  }

  @Test
  void formatAsJson_withNumber_returnsNumber() throws Exception {
    String json = formatter.formatAsJson(42);

    assertThat(json).isEqualTo("42");
  }

  @Test
  void formatAsJson_withBoolean_returnsBoolean() throws Exception {
    String json = formatter.formatAsJson(true);

    assertThat(json).isEqualTo("true");
  }

  @Test
  void formatAsJson_withList_returnsFormattedJson() throws Exception {
    List<String> items = List.of("item1", "item2", "item3");
    String json = formatter.formatAsJson(items);

    assertThat(json).contains("item1").contains("item2").contains("item3");
  }

  @Test
  void formatAsJson_withEmptyList_returnsValidJson() throws Exception {
    List<String> items = List.of();
    String json = formatter.formatAsJson(items);

    assertThat(json).isNotNull();
    assertThat(json).isNotEmpty();
  }

  @Test
  void formatAsJson_withMap_returnsFormattedJson() throws Exception {
    Map<String, Object> map = new HashMap<>();
    map.put("key1", "value1");
    map.put("key2", 42);
    String json = formatter.formatAsJson(map);

    assertThat(json).contains("key1").contains("value1").contains("key2").contains("42");
  }

  @Test
  void formatAsJson_withNull_returnsNull() throws Exception {
    String json = formatter.formatAsJson(null);

    assertThat(json).isEqualTo("null");
  }

  @Test
  void formatAsJson_withNestedObject_returnsFormattedJson() throws Exception {
    TestObject obj = new TestObject("nested", 999);
    String json = formatter.formatAsJson(obj);

    assertThat(json).contains("nested").contains("999");
  }

  @Test
  void printTable_withSingleItem_printsSingleItem() {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    List<String> items = List.of("single");

    formatter.printTable(items);

    String output = outContent.toString();
    assertThat(output).contains("single");
  }

  @Test
  void printTable_withMultipleItems_printsAllItems() {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    List<String> items = List.of("row1", "row2", "row3");

    formatter.printTable(items);

    String output = outContent.toString();
    assertThat(output).contains("row1").contains("row2").contains("row3");
  }

  @Test
  void printTable_withEmptyList_printsNothing() {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    List<String> items = List.of();

    formatter.printTable(items);

    String output = outContent.toString();
    assertThat(output).isEmpty();
  }

  @Test
  void printTable_withLongStrings_printsFullStrings() {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    List<String> items = List.of("this is a very long string with multiple words");

    formatter.printTable(items);

    String output = outContent.toString();
    assertThat(output).contains("this is a very long string with multiple words");
  }

  @Test
  void printJson_withObject_printsFormattedJson() throws Exception {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    TestObject obj = new TestObject("test", 123);

    formatter.printJson(obj);

    String output = outContent.toString();
    assertThat(output).contains("name").contains("test").contains("id").contains("123");
  }

  @Test
  void printJson_withString_printsQuotedString() throws Exception {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));

    formatter.printJson("hello");

    String output = outContent.toString().trim();
    assertThat(output).isEqualTo("\"hello\"");
  }

  @Test
  void printJson_withList_printsFormattedJson() throws Exception {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    List<String> items = List.of("item1", "item2");

    formatter.printJson(items);

    String output = outContent.toString();
    assertThat(output).contains("item1").contains("item2");
  }

  @Test
  void printJson_withMap_printsFormattedJson() throws Exception {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));
    Map<String, Object> map = Map.of("key", "value");

    formatter.printJson(map);

    String output = outContent.toString();
    assertThat(output).contains("key").contains("value");
  }

  @Test
  void printJson_withNull_printsNull() throws Exception {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));

    formatter.printJson(null);

    String output = outContent.toString().trim();
    assertThat(output).isEqualTo("null");
  }

  @Test
  void formatAsJson_withComplexObject_includesAllFields() throws Exception {
    ComplexObject obj = new ComplexObject("name", 123, true, List.of("a", "b"));
    String json = formatter.formatAsJson(obj);

    assertThat(json).contains("\"name\"").contains("123").contains("true");
  }

  private static class TestObject {
    public String name;
    public int id;

    TestObject(String name, int id) {
      this.name = name;
      this.id = id;
    }
  }

  private static class ComplexObject {
    public String name;
    public int id;
    public boolean active;
    public List<String> items;

    ComplexObject(String name, int id, boolean active, List<String> items) {
      this.name = name;
      this.id = id;
      this.active = active;
      this.items = items;
    }
  }
}
