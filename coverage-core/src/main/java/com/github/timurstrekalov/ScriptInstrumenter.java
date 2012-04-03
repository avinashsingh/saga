package com.github.timurstrekalov;

import com.gargoylesoftware.htmlunit.ScriptPreProcessor;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import net.sourceforge.htmlunit.corejs.javascript.Parser;
import net.sourceforge.htmlunit.corejs.javascript.Token;
import net.sourceforge.htmlunit.corejs.javascript.ast.*;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static net.sourceforge.htmlunit.corejs.javascript.Token.*;

class ScriptInstrumenter implements ScriptPreProcessor {

    private final String coverageVariableName;
    private final String initializingCode;
    private final String arrayInitializer;
    private final File outputDir;
    private final boolean outputInstrumentedFiles;

    private final List<ScriptData> scriptDataList = Lists.newLinkedList();

    private final Collection<Pattern> ignorePatterns;

    public ScriptInstrumenter(
            final String coverageVariableName,
            final Collection<String> ignorePatterns,
            final File outputDir,
            final boolean outputInstrumentedFiles) {

        this.coverageVariableName = coverageVariableName;
        this.outputDir = outputDir;
        this.outputInstrumentedFiles = outputInstrumentedFiles;

        initializingCode = String.format("%s = window.%s || {};%n", coverageVariableName, coverageVariableName);
        arrayInitializer = String.format("%s['%%s'][%%d] = 0;%n", coverageVariableName);

        this.ignorePatterns = Collections2.transform(ignorePatterns, new Function<String, Pattern>() {
            @Override
            public Pattern apply(final String input) {
                return Pattern.compile(input);
            }
        });
    }

    @Override
    public String preProcess(
            final HtmlPage htmlPage,
            final String sourceCode,
            final String sourceName,
            final int lineNumber,
            final HtmlElement htmlElement) {

        if (shouldIgnore(sourceName)) {
            return sourceCode;
        }

        final ScriptData data = new ScriptData(sourceName, sourceCode);
        scriptDataList.add(data);

        final AstRoot root = new Parser().parse(sourceCode, sourceName, lineNumber);
        root.visit(new InstrumentingVisitor(data));

        final String treeSource = root.toSource();
        final StringBuilder buf = new StringBuilder(
                initializingCode.length() +
                data.getNumberOfStatements() * arrayInitializer.length() +
                treeSource.length());

        buf.append(initializingCode);
        buf.append(String.format("%s['%s'] = {};%n", coverageVariableName, data.getHashedSourceName()));

        for (final Integer i : data.getLineNumbersOfAllStatements()) {
            buf.append(String.format(arrayInitializer, data.getHashedSourceName(), i));
        }

        buf.append(treeSource);

        final String instrumentedCode = buf.toString();

        if (outputInstrumentedFiles) {
            try {
                final File outputFile = new File(outputDir, new File(sourceName).getName() + "-instrumented.js");
                IOUtils.write(instrumentedCode, new FileOutputStream(outputFile));
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        return instrumentedCode;
    }

    private boolean shouldIgnore(final String sourceName) {
        return Iterables.any(ignorePatterns, new Predicate<Pattern>() {
            @Override
            public boolean apply(final Pattern input) {
                return input.matcher(sourceName).matches();
            }
        });
    }

    public List<ScriptData> getScriptDataList() {
        return scriptDataList;
    }

    private class InstrumentingVisitor implements NodeVisitor {

        private final ScriptData data;

        public InstrumentingVisitor(final ScriptData data) {
            this.data = data;
        }

        @Override
        public boolean visit(final AstNode node) {
            if (isExecutableBlock(node)) {
                addInstrumentationSnippetFor(node);
            }

            return true;
        }

        private boolean isExecutableBlock(final AstNode node) {
            final AstNode parent = node.getParent();
            if (parent == null) {
                return false;
            }

            final int type = node.getType();
            final int parentType = parent.getType();

            return type == SWITCH
                    || type == FOR
                    || type == DO
                    || type == WHILE
                    || type == CONTINUE
                    || type == BREAK
                    || type == TRY
                    || type == THROW
                    || type == CASE
                    || type == IF
                    || type == EXPR_RESULT
                    || type == EXPR_VOID
                    || type == RETURN
                    || (type == FUNCTION && (parentType == SCRIPT || parentType == BLOCK))
                    || (type == VAR && node.getClass() == VariableDeclaration.class && parentType != FOR);
        }

        private void addInstrumentationSnippetFor(final AstNode node) {
            final AstNode parent = node.getParent();

            final int type = node.getType();
            final int parentType = parent.getType();

            if (type == CASE) {
                handleSwitchCase((SwitchCase) node);
            } else if (type == IF && parentType == IF) {
                flattenElseIf((IfStatement) node, (IfStatement) parent);
            } else {
                if (parentType != CASE) {
                    final int lineNr = node.getLineno();

                    data.addExecutableLine(lineNr, node.getLength());
                    parent.addChildBefore(newInstrumentationNode(lineNr), node);
                }
            }
        }

        /**
         * 'switch' statement cases are special in the sense that their children are not actually their children,
         * meaning the children have a reference to their parent, but the parent only has a List of all its
         * children, so we can't just addChildBefore() like we do for all other cases.
         *
         * They do, however, retain a list of all statements per each case, which we're using here
         */
        private void handleSwitchCase(final SwitchCase switchCase) {
            final List<AstNode> newStatements = Lists.newArrayList();

            for (final AstNode statement : switchCase.getStatements()) {
                final int lineNr = statement.getLineno();
                data.addExecutableLine(lineNr, switchCase.getLength());

                newStatements.add(newInstrumentationNode(lineNr));
                newStatements.add(statement);
            }

            switchCase.setStatements(newStatements);
        }

        /**
         * In order to make it possible to cover else-if blocks, we're flattening the shorthand else-if
         *
         * <pre>
         * {@literal
         * if (cond1) {
         *     doIf();
         * } else if (cond2) {
         *     doElseIf();
         * } else {
         *     doElse();
         * }
         * }
         * </pre>
         *
         * into
         *
         * <pre>
         * {@literal
         * if (cond1) {
         *     doIf();
         * } else {
         *     if (cond2) {
         *         doElseIf();
         *     } else {
         *         doElse();
         *     }
         * }
         * }
         * </pre>
         */
        private void flattenElseIf(final IfStatement elseIfStatement, final IfStatement ifStatement) {
            final Scope scope = new Scope();
            scope.addChild(elseIfStatement);

            ifStatement.setElsePart(scope);

            final int lineNr = elseIfStatement.getLineno();

            data.addExecutableLine(lineNr, elseIfStatement.getLength());
            scope.addChildBefore(newInstrumentationNode(lineNr), elseIfStatement);
        }

        private AstNode newInstrumentationNode(final int lineNr) {
            final ExpressionStatement instrumentationNode = new ExpressionStatement();
            final UnaryExpression inc = new UnaryExpression();

            inc.setIsPostfix(true);
            inc.setOperator(Token.INC);

            final ElementGet outer = new ElementGet();
            final ElementGet inner = new ElementGet();

            outer.setTarget(inner);

            final Name covDataVar = new Name();
            covDataVar.setIdentifier(coverageVariableName);

            inner.setTarget(covDataVar);

            final StringLiteral fileName = new StringLiteral();
            fileName.setValue(data.getHashedSourceName());
            fileName.setQuoteCharacter('\'');

            inner.setElement(fileName);

            final NumberLiteral index = new NumberLiteral();
            index.setValue(Integer.toString(lineNr));

            outer.setElement(index);

            inc.setOperand(outer);

            instrumentationNode.setExpression(inc);
            instrumentationNode.setHasResult();

            return instrumentationNode;
        }

    }

}
