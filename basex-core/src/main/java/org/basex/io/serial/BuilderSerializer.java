package org.basex.io.serial;

import static org.basex.util.Token.*;

import java.io.*;

import org.basex.build.*;
import org.basex.data.*;
import org.basex.util.*;

/**
 * A serializer that pipes the events directly through to a builder.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Leo Woerteler
 */
public class BuilderSerializer extends Serializer {
  /** Attribute cache. */
  private final Atts atts = new Atts();
  /** Namespace cache. */
  private final Atts nsp = new Atts();
  /** The builder. */
  private final Builder build;

  /**
   * Constructor taking a Builder.
   * @param builder builder to be used
   */
  public BuilderSerializer(final Builder builder) {
    build = builder;
  }

  @Override
  protected final void text(final byte[] value, final FTPos ftp) throws IOException {
    build.text(value);
  }

  @Override
  protected final void pi(final byte[] name, final byte[] value) throws IOException {
    build.pi(concat(name, SPACE, value));
  }

  @Override
  protected final void finishOpen() throws IOException {
    build.openElem(elem, atts, nsp);
    atts.clear();
    nsp.clear();
  }

  @Override
  protected void finishEmpty() throws IOException {
    build.emptyElem(elem, atts, nsp);
    atts.clear();
    nsp.clear();
  }

  @Override
  protected void finishClose() throws IOException {
    build.closeElem();
  }

  @Override
  protected final void comment(final byte[] value) throws IOException {
    build.comment(value);
  }

  @Override
  protected final void attribute(final byte[] name, final byte[] value) {
    if(startsWith(name, XMLNS)) {
      if(name.length == 5) {
        nsp.add(EMPTY, value);
      } else if(name[5] == ':') {
        nsp.add(substring(name, 6), value);
      } else {
        atts.add(name, value);
      }
    } else {
      atts.add(name, value);
    }
  }

  @Override
  protected void openDoc(final byte[] name) throws IOException {
    build.openDoc(name);
  }

  @Override
  protected final void closeDoc() throws IOException {
    build.closeDoc();
  }
}
