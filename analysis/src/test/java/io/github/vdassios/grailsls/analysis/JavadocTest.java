package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JavadocTest {
  @Test
  void theDescriptionBecomesMarkdownWithoutTheBlockTags() {
    String comment = String.join("\n",
        "/**",
        "   * Returns {@code true} if, and only if, {@link #length()} is {@code 0}.",
        "   * See {@link java.util.List#of(Object) the list} and {@linkplain Other that}.",
        "   * <p>",
        "   * Use <b>with</b> care: {@literal a < b} &amp; more.",
        "   *",
        "   * @param name is {@code ignored}",
        "   *        over two lines",
        "   * @return nothing",
        "   */");
    assertEquals("Returns `true` if, and only if, `length()` is `0`.\nSee the list and that.\n\nUse **with** care: a < b & more.", Javadoc.markdown(comment));
  }

  /** An annotation at the start of a line inside {@code <pre>} is code, not a block tag. */
  @Test
  void aLineStartingWithAtInsideAPreBlockIsKept() {
    String comment = String.join("\n",
        "/**",
        " * Saves in a transaction:",
        " * <pre>",
        " * @Transactional",
        " * class BookService {}",
        " * </pre>",
        " * @see Transactional",
        " */");
    assertEquals("Saves in a transaction:\n\n```\n\n@Transactional\nclass BookService {}\n\n```", Javadoc.markdown(comment));
  }

  @Test
  void aOneLineCommentAndAnEmptyOne() {
    assertEquals("Says hello.", Javadoc.markdown("/** Says hello. */"));
    assertNull(Javadoc.markdown("/**\n * @return nothing\n */"));
  }
}
