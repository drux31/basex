package org.basex.query.util.json;

import java.util.*;

import org.basex.build.file.*;
import org.basex.build.file.JsonProp.Spec;
import org.basex.query.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.map.Map;
import org.basex.query.value.seq.*;
import org.basex.util.*;


/**
 * <p>Provides a method for parsing a JSON string and converting it to an XQuery
 * item made of nested maps.
 *
 * <p>The mapping from JSON to XQuery is the following:
 * <p><dl>
 *   <dt>string<dd>xs:string
 *   <dt>number<dd>xs:double
 *   <dt>boolean<dd>xs:boolean
 *   <dt>null<dd>an empty sequence <code>()</code>
 *   <dt>array (e.g. {@code ["foo", true, 123]})
 *   <dd>an XQuery map with integer keys, starting by 1 (e.g.
 *     <code>{1:'foo', 2:true(), 3:123}</code>)
 *   <dt>object (e.g. <code>{"foo": 42, "bar": null}</code>)
 *   <dd>an XQuery map (e.g.
 *     <code>{'foo':42, 'bar':()}</code>)
 * </dl>
 *
 * @author BaseX Team 2005-12, BSD License
 * @author Leo Woerteler
 */
public final class JsonMapConverter extends JsonConverter implements JsonHandler {
  /** Stack for intermediate values. */
  private final Stack<Value> stack = new Stack<Value>();
  /** JSON spec. */
  private final Spec spec;
  /** Unescape flag. */
  private final boolean unescape;

  /**
   * Constructor.
   * @param jp json properties
   * @param ii input info
   */
  public JsonMapConverter(final JsonProp jp, final InputInfo ii) {
    super(ii);
    spec = jp.spec();
    unescape = jp.is(JsonProp.UNESCAPE);
  }

  @Override
  public Item convert(final String in) throws QueryException {
    stack.clear();
    JsonParser.parse(in, spec, unescape, this, info);
    return stack.peek().isEmpty() ? null : (Item) stack.pop();
  }

  @Override
  public void openObject() {
    stack.push(Map.EMPTY);
  }

  @Override
  public void openPair(final byte[] key) {
    stack.push(Str.get(key));
  }

  @Override
  public void closePair() throws QueryException {
    final Value val = stack.pop();
    final Item key = (Item) stack.pop();
    final Map map = (Map) stack.pop();
    stack.push(map.insert(key, val, null));
  }

  @Override public void closeObject() { }

  @Override
  public void openArray() {
    stack.push(Map.EMPTY);
  }

  @Override
  public void openItem() {
    stack.push(Int.get(((Map) stack.peek()).mapSize() + 1));
  }

  @Override
  public void closeItem() throws QueryException {
    closePair();
  }

  @Override public void closeArray() { }

  @Override
  public void openConstr(final byte[] name) {
    openObject();
    openPair(name);
    openArray();
  }

  @Override public void openArg() {
    openItem();
  }

  @Override public void closeArg() throws QueryException {
    closeItem();
  }

  @Override
  public void closeConstr() throws QueryException {
    closeArray();
    closePair();
    closeObject();
  }

  @Override
  public void numberLit(final byte[] val) throws QueryException {
    stack.push(Dbl.get(val, info));
  }

  @Override
  public void stringLit(final byte[] value) {
    stack.push(Str.get(value));
  }

  @Override
  public void nullLit() {
    stack.push(Empty.SEQ);
  }

  @Override
  public void booleanLit(final byte[] b) {
    stack.push(Bln.get(Token.eq(b, Token.TRUE)));
  }
}
