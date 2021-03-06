package org.basex.tests.w3c;

import static org.basex.tests.w3c.QT3Constants.*;
import static org.basex.util.Prop.NL;
import static org.basex.util.Token.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

import javax.xml.namespace.*;

import org.basex.core.*;
import org.basex.io.*;
import org.basex.io.out.*;
import org.basex.io.serial.*;
import org.basex.query.*;
import org.basex.query.func.*;
import org.basex.query.func.fn.Compare.Mode;
import org.basex.query.value.item.*;
import org.basex.query.value.type.*;
import org.basex.tests.bxapi.*;
import org.basex.tests.bxapi.xdm.*;
import org.basex.util.*;
import org.basex.util.options.Options.YesNo;

/**
 * Driver for the XQuery/XPath/XSLT 3.* Test Suite, located at
 * {@code http://dev.w3.org/2011/QT3-test-suite/}. The driver needs to be
 * executed from the test suite directory.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Christian Gruen
 */
public final class QT3TS extends Main {
  /** EQName pattern. */
  private static final Pattern BIND = Pattern.compile("^Q\\{(.*?)\\}(.+)$");

  /** Test suite id. */
  private final String testid = "qt3ts";
  /** Path to the test suite (ignored if {@code null}). */
  private String basePath;

  /** Maximum length of result output. */
  private int maxout = 2000;

  /** Correct results. */
  private final TokenBuilder right = new TokenBuilder();
  /** Wrong results. */
  private final TokenBuilder wrong = new TokenBuilder();
  /** Ignored tests. */
  private final TokenBuilder ignore = new TokenBuilder();
  /** Report builder. */
  private QT3TSReport report;

  /** Number of total queries. */
  private int total;
  /** Number of tested queries. */
  private int tested;
  /** Number of correct queries. */
  private int correct;
  /** Number of ignored queries. */
  private int ignored;

  /** Current base uri. */
  private String baseURI;
  /** Current base directory. */
  private String baseDir;

  /** Slow queries flag. */
  private TreeMap<Long, String> slow;
  /** Query filter string. */
  private String single = "";
  /** Verbose flag. */
  private boolean verbose;
  /** Error code flag. */
  private boolean errors = true;
  /** Also print ignored files. */
  private boolean ignoring;
  /** All flag. */
  private boolean all;

  /** Database context. */
  protected final Context ctx = new Context();
  /** Global environments. */
  private final ArrayList<QT3Env> genvs = new ArrayList<>();

  /**
   * Main method of the test class.
   * @param args command-line arguments
   * @throws Exception exception
   */
  public static void main(final String[] args) throws Exception {
    try {
      new QT3TS(args).run();
    } catch(final IOException ex) {
      Util.errln(ex);
      System.exit(1);
    }
  }

  /**
   * Constructor.
   * @param args command-line arguments
   */
  protected QT3TS(final String[] args) {
    super(args);
  }


  /**
   * Runs all tests.
   * @throws Exception exception
   */
  private void run() throws Exception {
    ctx.soptions.set(StaticOptions.DBPATH, sandbox().path() + "/data");
    parseArgs();
    init();

    final Performance perf = new Performance();
    ctx.options.set(MainOptions.CHOP, false);

    final SerializerOptions sopts = new SerializerOptions();
    sopts.set(SerializerOptions.METHOD, SerialMethod.XML);
    sopts.set(SerializerOptions.INDENT, YesNo.NO);
    sopts.set(SerializerOptions.OMIT_XML_DECLARATION, YesNo.NO);
    ctx.options.set(MainOptions.SERIALIZER, sopts);

    final XdmValue doc = new XQuery("doc('" + file(false, CATALOG) + "')", ctx).value();
    final String version = asString("*:catalog/@version", doc);
    Util.outln(NL + "QT3 Test Suite " + version);
    Util.outln("Test directory: " + file(false, "."));
    Util.out("Parsing queries");

    for(final XdmItem ienv : new XQuery("*:catalog/*:environment", ctx).context(doc))
      genvs.add(new QT3Env(ctx, ienv));

    for(final XdmItem it : new XQuery("for $f in //*:test-set/@file return string($f)",
        ctx).context(doc)) testSet(it.getString());

    final StringBuilder result = new StringBuilder();
    result.append(" Rate    : ").append(pc(correct, tested)).append(NL);
    result.append(" Total   : ").append(total).append(NL);
    result.append(" Tested  : ").append(tested).append(NL);
    result.append(" Wrong   : ").append(tested - correct).append(NL);
    result.append(" Ignored : ").append(ignored).append(NL);

    // save log data
    Util.outln(NL + "Writing log file '" + testid + ".log'...");
    try(final PrintOutput po = new PrintOutput(testid + ".log")) {
      po.println("QT3TS RESULTS __________________________" + NL);
      po.println(result.toString());
      po.println("WRONG __________________________________" + NL);
      po.print(wrong.finish());
      if(all || !single.isEmpty()) {
        po.println("CORRECT ________________________________" + NL);
        po.print(right.finish());
      }
      if(ignoring) {
        po.println("IGNORED ________________________________" + NL);
        po.print(ignore.finish());
      }
    }

    // save report
    if(report != null) {
      final String file = "ReportingResults/results_" +
          Prop.NAME + "_" + Prop.VERSION + IO.XMLSUFFIX;
      new IOFile(file).write(report.create(ctx).toArray());
      Util.outln("Creating report '" + file + "'...");
    }

    Util.out(NL + result);
    Util.outln(" Time    : " + perf);

    if(slow != null && !slow.isEmpty()) {
      Util.outln(NL + "Slow queries:");
      for(final Map.Entry<Long, String> l : slow.entrySet()) {
        Util.outln("- " + -(l.getKey() / 1000000) + " ms: " + l.getValue());
      }
    }

    ctx.close();
    sandbox().delete();
  }

  /**
   * Runs a single test set.
   * @param name name of test set
   * @throws Exception exception
   */
  private void testSet(final String name) throws Exception {
    final XdmValue doc = new XQuery("doc(' " + file(false, name) + "')", ctx).value();
    final XdmValue set = new XQuery("*:test-set", ctx).context(doc).value();
    final IO base = IO.get(doc.getBaseURI());
    baseURI = base.path();
    baseDir = base.dir();

    if(supported(set)) {
      // parse environment of test-set
      final ArrayList<QT3Env> envs = new ArrayList<>();
      for(final XdmItem ienv : new XQuery("*:environment", ctx).context(set))
        envs.add(new QT3Env(ctx, ienv));

      if(report != null) report.addSet(asString("@name", set));

      // run all test cases
      for(final XdmItem its : new XQuery("*:test-case", ctx).context(set)) {
        try {
          testCase(its, envs);
        } catch(final IOException ex) {
          Util.debug(ex);
        }
      }
    }
  }

  /**
   * Runs a single test case.
   * @param test node
   * @param envs environments
   * @throws Exception exception
   */
  private void testCase(final XdmItem test, final ArrayList<QT3Env> envs)
      throws Exception {

    if(total++ % 500 == 0) Util.out(".");

    if(!supported(test)) {
      if(ignoring) ignore.add(asString("@name", test)).add(NL);
      ignored++;
      return;
    }

    // skip queries that do not match filter
    final String name = asString("@name", test);
    if(!name.startsWith(single)) {
      if(ignoring) ignore.add(name).add(NL);
      ignored++;
      return;
    }

    tested++;

    // expected result
    final XdmValue expected = new XQuery("*:result/*[1]", ctx).context(test).value();

    // check if environment is defined in test-case
    QT3Env env = null;
    final XdmValue ienv = new XQuery("*:environment[*]", ctx).context(test).value();
    if(ienv.size() != 0) env = new QT3Env(ctx, ienv);

    // parse local environment
    boolean base = true;
    if(env == null) {
      final String e = asString("*:environment[1]/@ref", test);
      if(!e.isEmpty()) {
        // check if environment is defined in test-set
        env = envs(envs, e);
        // check if environment is defined in catalog
        if(env == null) {
          env = envs(genvs, e);
          base = false;
        }
        if(env == null) Util.errln("%: environment '%' not found.", name, e);
      }
    }

    // retrieve query to be run
    final Performance perf = new Performance();
    final String qfile = asString("*:test/@file", test);
    String string;
    if(qfile.isEmpty()) {
      // get query string
      string = asString("*:test", test);
    } else {
      // get query from file
      string = string(new IOFile(baseDir, qfile).read());
    }

    if(verbose) Util.outln(name);

    // bind variables
    if(env != null) {
      for(final HashMap<String, String> par : env.params) {
        final String decl = par.get(DECLARED);
        if(decl == null || decl.equals("false")) {
          string = "declare variable $" + par.get(NNAME) + " external;" + string;
        }
      }
      // bind documents
      for(final HashMap<String, String> src : env.sources) {
        final String role = src.get(ROLE);
        if(role != null && role.startsWith("$")) {
          string = "declare variable " + role + " external;" + string;
        }
      }
    }

    final XQuery query = new XQuery(string, ctx);
    if(base) query.baseURI(baseURI);

    // add modules
    final String qm = "for $m in *:module return ($m/@uri, $m/@file)";
    final XQuery qmod = new XQuery(qm, ctx).context(test);
    while(true) {
      final XdmItem uri = qmod.next();
      if(uri == null) break;
      final XdmItem file = qmod.next();
      if(file == null) break;
      query.addModule(uri.getString(), baseDir + file.getString());
    }

    final QT3Result returned = new QT3Result();
    try {
      if(env != null) {
        // bind namespaces
        for(final HashMap<String, String> ns : env.namespaces) {
          query.namespace(ns.get(PREFIX), ns.get(URI));
        }
        // bind variables
        for(final HashMap<String, String> par : env.params) {
          query.bind(par.get(NNAME), new XQuery(par.get(SELECT), ctx).value());
        }
        // bind documents
        for(final HashMap<String, String> src : env.sources) {
          // add document reference
          final String file = file(base, src.get(FILE));
          query.addDocument(src.get(URI), file);
          final String role = src.get(ROLE);
          if(role == null) continue;

          final XdmValue doc = query.document(file);
          if(role.equals(".")) query.context(doc);
          else query.bind(role, doc);
        }
        // bind resources
        for(final HashMap<String, String> src : env.resources) {
          query.addResource(src.get(URI), file(base, src.get(FILE)), src.get(ENCODING));
        }
        // bind collections
        query.addCollection(env.collURI, env.collSources.toArray());
        if(env.collContext) {
          query.context(query.collection(env.collURI));
        }
        // bind context item
        if(env.context != null) {
          query.context(env.context);
        }
        // set base uri
        if(env.baseURI != null) query.baseURI(env.baseURI);
        // bind decimal formats
        for(final Map.Entry<QName, HashMap<String, String>> df :
          env.decFormats.entrySet()) {
          query.decimalFormat(df.getKey(), df.getValue());
        }
      }

      // run query
      returned.value = query.value();
      returned.sprop = query.serializer();
    } catch(final XQueryException ex) {
      returned.xqerror = ex;
      returned.value = null;
    } catch(final Throwable ex) {
      // unexpected error (potential bug)
      returned.error = ex;
      Util.errln("Query: " + name);
      ex.printStackTrace();
    }

    if(slow != null) {
      final long l = perf.time();
      if(l > 100000000) slow.put(-l, name);
    }

    final String exp = test(returned, expected);
    final TokenBuilder tmp = new TokenBuilder();
    tmp.add(name).add(NL);
    tmp.add(noComments(string)).add(NL);

    boolean err = returned.value == null;
    String res;
    try {
      if(returned.error != null) {
        res = returned.error.toString();
      } else if(returned.xqerror != null) {
        res = returned.xqerror.getCode() + ": " + returned.xqerror.getLocalizedMessage();
      } else {
        returned.sprop.set(SerializerOptions.OMIT_XML_DECLARATION, "yes");
        res = serialize(returned.value, returned.sprop);
      }
    } catch(final XQueryException ex) {
      res = ex.getCode() + ": " + ex.getLocalizedMessage();
      err = true;
    } catch(final Throwable ex) {
      res = returned.value.toString();
    }

    tmp.add(err ? "Error : " : "Result: ").add(noComments(res)).add(NL);
    if(exp == null) {
      tmp.add(NL);
      right.add(tmp.finish());
      correct++;
    } else {
      wrong.add(tmp.add("Expect: ").add(noComments(exp)).add(NL).add(NL).finish());
    }
    if(report != null) report.addTest(name, exp == null);
  }

  /**
   * Removes comments from the specified string.
   * @param in input string
   * @return result
   */
  private String noComments(final String in) {
    return QueryProcessor.removeComments(in, maxout);
  }

  /** Flags for dependencies that are not supported. */
  private static final String NOSUPPORT =
    "('schema-location-hint','schemaAware','schemaImport'," +
    "'schemaValidation','staticTyping')";

  /**
   * Checks if the current test case is supported.
   * @param test test case
   * @return result of check
   */
  private boolean supported(final XdmValue test) {
    // the following query generates a result if the specified test is not supported
    return new XQuery(
      "*:environment/*:collation |" + // skip collation tests
      "*:dependency[" +
      // skip schema imports, schema validation, namespace axis, static typing
      "@type = 'feature' and (" +
      " @value = " + NOSUPPORT + " and (@satisfied = 'true' or empty(@satisfied)) or" +
      " @value != " + NOSUPPORT + "and @satisfied = 'false') or " +
      // skip fully-normalized unicode tests
      "@type = 'unicode-normalization-form' and @value = 'FULLY-NORMALIZED' or " +
      // skip xml/xsd 1.1 tests
      "@type = ('xml-version', 'xsd-version') and @value = ('1.1', '1.0:4-') or " +
      // skip limits
      "@type = 'limits' and @value = ('big_integer') or " +
      // skip non-XQuery tests
      "@type = 'spec' and not(matches(@value, 'XQ\\d\\d\\+'))" +
      "]", ctx).context(test).value().size() == 0;
  }

  /**
   * Returns the specified environment, or {@code null}.
   * @param envs environments
   * @param ref reference
   * @return environment
   */
  private static QT3Env envs(final ArrayList<QT3Env> envs, final String ref) {
    for(final QT3Env e : envs) if(e.name.equals(ref)) return e;
    return null;
  }

  /**
   * Tests the result of a test case.
   * @param returned resulting value
   * @param expected expected result
   * @return {@code null} if test was successful; otherwise, expected test suite result
   */
  private String test(final QT3Result returned, final XdmValue expected) {
    final String type = expected.getName().getLocalPart();
    final XdmValue value = returned.value;

    try {
      String msg;
      if(type.equals("error")) {
        msg = assertError(returned, expected);
      } else if(type.equals("assert-serialization-error")) {
        msg = assertSerializationError(returned, expected, returned.sprop);
      } else if(type.equals("all-of")) {
        msg = allOf(returned, expected);
      } else if(type.equals("any-of")) {
        msg = anyOf(returned, expected);
      } else if(value != null) {
        if(type.equals("assert")) {
          msg = assertQuery(value, expected);
        } else if(type.equals("assert-count")) {
          msg = assertCount(value, expected);
        } else if(type.equals("assert-deep-eq")) {
          msg = assertDeepEq(value, expected);
        } else if(type.equals("assert-empty")) {
          msg = assertEmpty(value);
        } else if(type.equals("assert-eq")) {
          msg = assertEq(value, expected);
        } else if(type.equals("assert-false")) {
          msg = assertBoolean(value, false);
        } else if(type.equals("assert-permutation")) {
          msg = assertPermutation(value, expected);
        } else if(type.equals("assert-xml")) {
          msg = assertXML(value, expected);
        } else if(type.equals("serialization-matches")) {
          msg = serializationMatches(value, expected, returned.sprop);
        } else if(type.equals("assert-string-value")) {
          msg = assertStringValue(value, expected);
        } else if(type.equals("assert-true")) {
          msg = assertBoolean(value, true);
        } else if(type.equals("assert-type")) {
          msg = assertType(value, expected);
        } else {
          msg = "Test type not supported: " + type;
        }
      } else {
        msg = expected.toString();
      }
      return msg;
    } catch(final Exception ex) {
      ex.printStackTrace();
      return "Exception: " + ex.getMessage();
    }
  }

  /**
   * Tests error.
   * @param returned query result
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertError(final QT3Result returned, final XdmValue expected) {
    final String exp = asString('@' + CODE, expected);
    if(returned.xqerror == null) return exp;
    if(!errors || exp.equals("*")) return null;

    final QNm resErr = returned.xqerror.getException().qname();

    String name = exp, uri = string(QueryText.ERROR_URI);
    final Matcher m = BIND.matcher(exp);
    if(m.find()) {
      uri = m.group(1);
      name = m.group(2);
    }
    final QNm expErr = new QNm(name, uri);
    return expErr.eq(resErr) ? null : exp;
  }

  /**
   * Tests all-of.
   * @param res resulting value
   * @param exp expected result
   * @return optional expected test suite result
   */
  private String allOf(final QT3Result res, final XdmValue exp) {
    final TokenBuilder tb = new TokenBuilder();
    for(final XdmItem it : new XQuery("*", ctx).context(exp)) {
      final String msg = test(res, it);
      if(msg != null) tb.add(tb.isEmpty() ? "" : ", ").add(msg);
    }
    return tb.isEmpty() ? null : tb.toString();
  }

  /**
   * Tests any-of.
   * @param res resulting value
   * @param exp expected result
   * @return optional expected test suite result
   */
  private String anyOf(final QT3Result res, final XdmValue exp) {
    final TokenBuilder tb = new TokenBuilder();
    for(final XdmItem it : new XQuery("*", ctx).context(exp)) {
      final String msg = test(res, it);
      if(msg == null) return null;
      tb.add(tb.isEmpty() ? "" : ", ").add(msg);
    }
    return "any of { " + tb + " }";
  }

  /**
   * Tests assertion.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertQuery(final XdmValue returned, final XdmValue expected) {
    final String exp = expected.getString();
    try {
      final String qu = "declare variable $result external; " + exp;
      return new XQuery(qu, ctx).bind("$result", returned).value().getBoolean() ? null : exp;
    } catch(final XQueryException ex) {
      // should not occur
      return ex.getException().getMessage();
    }
  }

  /**
   * Tests count.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private static String assertCount(final XdmValue returned, final XdmValue expected) {
    final long exp = expected.getInteger();
    final int res = returned.size();
    return exp == res ? null : Util.info("% items (% found)", exp, res);
  }

  /**
   * Tests equality.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertEq(final XdmValue returned, final XdmValue expected) {
    final String exp = expected.getString();
    try {
      final String qu = "declare variable $returned external; $returned eq " + exp;
      return new XQuery(qu, ctx).bind("$returned", returned).value().getBoolean() ? null : exp;
    } catch(final XQueryException err) {
      // numeric overflow: try simple string comparison
      return err.getCode().equals("FOAR0002") && exp.equals(expected.getString())
          ? null : err.getException().getMessage();
    }
  }

  /**
   * Tests deep equals.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertDeepEq(final XdmValue returned, final XdmValue expected) {
    final XdmValue exp = new XQuery(expected.getString(), ctx).value();
    return exp.deepEqual(returned) ? null : exp.toString();
  }

  /**
   * Tests permutation.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertPermutation(final XdmValue returned, final XdmValue expected) {
    // cache expected results
    final HashSet<String> exp = new HashSet<>();
    for(final XdmItem it : new XQuery(expected.getString(), ctx))
      exp.add(it.getString());
    // cache actual results
    final HashSet<String> res = new HashSet<>();
    for(final XdmItem it : returned) res.add(it.getString());

    if(exp.size() != res.size())
      return Util.info("% results (found: %)", exp.size(), res.size());

    for(final String s : exp.toArray(new String[exp.size()])) {
      if(!res.contains(s)) return Util.info("% (missing)", s);
    }
    for(final String s : res.toArray(new String[exp.size()])) {
      if(!exp.contains(s))
        return Util.info("% (missing in expected result)", s);
    }
    return null;
  }

  /**
   * Tests the serialized result.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertXML(final XdmValue returned, final XdmValue expected) {
    final String file = asString("@file", expected);
    final boolean norm = asBoolean("@normalize-space=('true','1')", expected);
    final boolean pref = asBoolean("@ignore-prefixes=('true','1')", expected);

    String exp = expected.getString();
    try {
      if(!file.isEmpty()) exp = string(new IOFile(baseDir, file).read());
      exp = normNL(exp);
      if(norm) exp = string(normalize(token(exp)));

      final String res = normNL(
          asString("serialize(., map{ 'indent':'no','method':'xml' })", returned));
      if(exp.equals(res)) return null;
      final String r = normNL(asString(
          "serialize(., map{ 'indent':'no','method':'xml','omit-xml-declaration':'no' })", returned));
      if(exp.equals(r)) return null;

      // include check for comments, processing instructions and namespaces
      String flags = "'" + Mode.ALLNODES + "'";
      if(!pref) flags += ",'" + Mode.NAMESPACES + "'";
      final String query = Function.DEEP_EQUAL_OPT.args("<X>" + exp + "</X>",
          "<X>" + res + "</X>" , "(" + flags + ")");
      return asBoolean(query, expected) ? null : exp;
    } catch(final IOException ex) {
      return Util.info("% (found: %)", exp, ex);
    }
  }

  /**
   * Tests the serialized result.
   * @param returned resulting value
   * @param expected expected result
   * @param sprop serialization properties
   * @return optional expected test suite result
   */
  private String serializationMatches(final XdmValue returned, final XdmValue expected,
      final SerializerOptions sprop) {

    final String exp = expected.getString();
    final String flags = asString("@flags", expected);
    final int flgs = flags.contains("i") ? Pattern.CASE_INSENSITIVE : 0;
    final Pattern pat = Pattern.compile("^.*" + exp + ".*", flgs |
        Pattern.MULTILINE | Pattern.DOTALL);

    try {
      return pat.matcher("^.*" + serialize(returned, sprop) + ".*").matches() ? null : exp;
    } catch(final IOException ex) {
      return Util.info("% (found: %)", exp, ex);
    }
  }


  /**
   * Tests a serialization error.
   * @param returned result
   * @param expected expected result
   * @param sprop serialization properties
   * @return optional expected test suite result
   */
  private String assertSerializationError(final QT3Result returned, final XdmValue expected,
      final SerializerOptions sprop) {

    final String expCode = asString('@' + CODE, expected);
    if(returned.xqerror != null) {
      if(!errors || expCode.equals("*")) return null;
      final String resCode = string(returned.xqerror.getException().qname().local());
      if(resCode.equals(expCode)) return null;
    }

    try {
      if(returned.value != null) serialize(returned.value, sprop);
      return expCode;
    } catch(final QueryIOException ex) {
      if(!errors || expCode.equals("*")) return null;
      final String resCode = string(ex.getCause().qname().local());
      if(resCode.equals(expCode)) return null;
      return Util.info("% (found: %)", expCode, ex);
    } catch(final IOException ex) {
      return Util.info("% (found: %)", expCode, ex);
    }
  }

  /**
   * Serializes values.
   * @param returned resulting value
   * @param sprop serialization properties
   * @return optional expected test suite result
   * @throws IOException I/O exception
   */
  private static String serialize(final XdmValue returned, final SerializerOptions sprop)
      throws IOException {

    final ArrayOutput ao = new ArrayOutput();
    try(final Serializer ser = Serializer.get(ao, sprop)) {
      for(final Item it : returned.internal()) ser.serialize(it);
    }
    return ao.toString();
  }

  /**
   * Tests string value.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertStringValue(final XdmValue returned, final XdmValue expected) {
    String exp = expected.getString();
    // normalize space
    final boolean norm = asBoolean("@normalize-space=('true','1')", expected);
    if(norm) exp = string(normalize(token(exp)));

    final TokenBuilder tb = new TokenBuilder();
    int c = 0;
    for(final XdmItem it : returned) {
      if(c++ != 0) tb.add(' ');
      tb.add(it.getString());
    }

    final String res = norm ? string(normalize(tb.finish())) : tb.toString();
    return exp.equals(res) ? null : exp;
  }

  /**
   * Tests boolean.
   * @param returned resulting value
   * @param expected expected
   * @return optional expected test suite result
   */
  private static String assertBoolean(final XdmValue returned, final boolean expected) {
    return returned.getType().eq(SeqType.BLN) && returned.getBoolean() == expected ?
        null : Util.info(expected);
  }

  /**
   * Tests empty sequence.
   * @param value resulting value
   * @return optional expected test suite result
   */
  private static String assertEmpty(final XdmValue value) {
    return value == XdmEmpty.EMPTY ? null : "";
  }

  /**
   * Tests type.
   * @param returned resulting value
   * @param expected expected result
   * @return optional expected test suite result
   */
  private String assertType(final XdmValue returned, final XdmValue expected) {
    final String exp = expected.getString();
    try {
      final String qu = "declare variable $returned external; $returned instance of " + exp;
      final XQuery query = new XQuery(qu, ctx);
      return query.bind("returned", returned).value().getBoolean() ? null :
        Util.info("Type '%' (found: '%')", exp, returned.getType().toString());
    } catch(final XQueryException ex) {
      // should not occur
      return ex.getException().getMessage();
    }
  }

  /**
   * Returns the string representation of a query result.
   * @param query query string
   * @param value optional context value
   * @return optional expected test suite result
   */
  String asString(final String query, final XdmValue value) {
    return XQuery.string(query, value, ctx);
  }

  /**
   * Returns the boolean representation of a query result.
   * @param query query string
   * @param value optional context value
   * @return optional expected test suite result
   */
  boolean asBoolean(final String query, final XdmValue value) {
    final XdmValue val = new XQuery(query, ctx).context(value).value();
    return val.size() != 0 && val.getBoolean();
  }

  /**
   * Returns the path to a given file.
   * @param base base flag.
   * @param file file name
   * @return path to the file
   */
  private String file(final boolean base, final String file) {
    final String dir = base ? baseDir : basePath;
    return dir == null ? file : new File(dir, file).getAbsolutePath();
  }

  /**
   * Calculates the percentage of correct queries.
   * @param v value
   * @param t total value
   * @return percentage
   */
  private static String pc(final int v, final long t) {
    return (t == 0 ? 100 : v * 10000 / t / 100d) + "%";
  }

  /**
   * Normalizes newline characters.
   * @param in input string
   * @return result
   */
  private static String normNL(final String in) {
    return in.replaceAll("\r\n|\r|\n", NL);
  }

  @Override
  protected void parseArgs() throws IOException {
    final MainParser arg = new MainParser(this);
    while(arg.more()) {
      if(arg.dash()) {
        final char c = arg.next();
        if(c == 'v') {
          verbose = true;
        } else if(c == 'a') {
          all = true;
        } else if(c == 'd') {
          Prop.debug = true;
        } else if(c == 'i') {
          ignoring = true;
        } else if(c == 'e') {
          errors = false;
        } else if(c == 'r') {
          report = new QT3TSReport();
        } else if(c == 's') {
          slow = new TreeMap<>();
        } else if(c == 'p') {
          final File f = new File(arg.string());
          if(!f.isDirectory()) throw arg.usage();
          basePath = f.getCanonicalPath();
        } else {
          throw arg.usage();
        }
      } else {
        single = arg.string();
        maxout = Integer.MAX_VALUE;
      }
    }
  }

  /**
   * Adds an environment variable required for function tests. Reference:
   *http://stackoverflow.com/questions/318239/how-do-i-set-environment-variables-from-java
   */
  @SuppressWarnings("unchecked")
  private static void init() {
    final Map<String, String> ne = new HashMap<>();
    ne.put("QTTEST", "42");
    ne.put("QTTEST2", "other");
    ne.put("QTTESTEMPTY", "");
    try {
      final Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
      final Field f = pe.getDeclaredField("theEnvironment");
      f.setAccessible(true);
      ((Map<String, String>) f.get(null)).putAll(ne);
      final Field f2 = pe.getDeclaredField("theCaseInsensitiveEnvironment");
      f2.setAccessible(true);
      ((Map<String, String>) f2.get(null)).putAll(ne);
    } catch(final NoSuchFieldException ex) {
      try {
        for(final Class<?> cl : Collections.class.getDeclaredClasses()) {
          if("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
            final Field f = cl.getDeclaredField("m");
            f.setAccessible(true);
            ((Map<String, String>) f.get(System.getenv())).putAll(ne);
          }
        }
      } catch(final Exception e2) {
        Util.errln("Test environment variable could not be set:" + e2);
      }
    } catch(final Exception e1) {
      Util.errln("Test environment variable could not be set: " + e1);
    }
  }

  /**
   * Structure for storing XQuery results.
   */
  static class QT3Result {
    /** Serialization parameters. */
    SerializerOptions sprop;
    /** Query result. */
    XdmValue value;
    /** Query exception. */
    XQueryException xqerror;
    /** Query error. */
    Throwable error;
  }

  /**
   * Returns the sandbox database path.
   * @return database path
   */
  private IOFile sandbox() {
    return new IOFile(Prop.TMP, testid);
  }

  @Override
  public String header() {
    return Util.info(Text.S_CONSOLE, Util.className(this));
  }

  @Override
  public String usage() {
    return " -v [pat]" + NL +
        " [pat] perform tests starting with a pattern" + NL +
        " -a  save all tests" + NL +
        " -d  debugging mode" + NL +
        " -e  ignore error codes" + NL +
        " -i  also save ignored files" + NL +
        " -p  path to the test suite" + NL +
        " -r  generate report file" + NL +
        " -s  print slow queries" + NL +
        " -v  verbose output";
  }
}
