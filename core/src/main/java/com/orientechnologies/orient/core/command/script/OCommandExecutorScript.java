/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */
package com.orientechnologies.orient.core.command.script;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.command.OCommandExecutorAbstract;
import com.orientechnologies.orient.core.command.OCommandRequest;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.core.sql.OCommandSQL;
import com.orientechnologies.orient.core.sql.OCommandSQLParsingException;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes Script Commands.
 * 
 * @see OCommandScript
 * @author Luca Garulli
 * 
 */
public class OCommandExecutorScript extends OCommandExecutorAbstract {
  protected OCommandScript request;

  public OCommandExecutorScript() {
  }

  @SuppressWarnings("unchecked")
  public OCommandExecutorScript parse(final OCommandRequest iRequest) {
    request = (OCommandScript) iRequest;
    return this;
  }

  public Object execute(final Map<Object, Object> iArgs) {
    return executeInContext(context, iArgs);
  }

  public Object executeInContext(final OCommandContext iContext, final Map<Object, Object> iArgs) {
    final String language = request.getLanguage();
    parserText = request.getText();

    if (language.equalsIgnoreCase("SQL"))
      // SPECIAL CASE: EXECUTE THE COMMANDS IN SEQUENCE
      return executeSQL();
    else
      return executeJsr223Script(language, iContext, iArgs);
  }

  public boolean isIdempotent() {
    return false;
  }

  protected Object executeJsr223Script(final String language, final OCommandContext iContext, final Map<Object, Object> iArgs) {
    ODatabaseDocumentInternal db = ODatabaseRecordThreadLocal.INSTANCE.get();

    final OScriptManager scriptManager = Orient.instance().getScriptManager();
    CompiledScript compiledScript = request.getCompiledScript();

    final ScriptEngine scriptEngine = scriptManager.acquireDatabaseEngine(db.getName(), language);
    try {

      if (compiledScript == null) {
        if (!(scriptEngine instanceof Compilable))
          throw new OCommandExecutionException("Language '" + language + "' does not support compilation");

        final Compilable c = (Compilable) scriptEngine;
        try {
          compiledScript = c.compile(parserText);
        } catch (ScriptException e) {
          scriptManager.throwErrorMessage(e, parserText);
        }

        request.setCompiledScript(compiledScript);
      }

      final Bindings binding = scriptManager.bind(compiledScript.getEngine().getBindings(ScriptContext.ENGINE_SCOPE),
          (ODatabaseDocumentTx) db, iContext, iArgs);

      try {
        final Object ob = compiledScript.eval(binding);

        return OCommandExecutorUtility.transformResult(ob);
      } catch (ScriptException e) {
        throw new OCommandScriptException("Error on execution of the script", request.getText(), e.getColumnNumber(), e);

      } finally {
        scriptManager.unbind(binding, iContext, iArgs);
      }
    } finally {
      scriptManager.releaseDatabaseEngine(db.getName(), scriptEngine);
    }
  }

  // TODO: CREATE A REGULAR JSR223 SCRIPT IMPL
  protected Object executeSQL() {
    ODatabaseDocument db = ODatabaseRecordThreadLocal.INSTANCE.getIfDefined();
    try {

      return executeSQLScript(db, parserText);

    } catch (IOException e) {
      throw new OCommandExecutionException("Error on executing command: " + parserText, e);
    }
  }

  @Override
  protected void throwSyntaxErrorException(String iText) {
    throw new OCommandScriptException("Error on execution of the script: " + iText, request.getText(), 0);
  }

  protected Object executeSQLScript(ODatabaseDocument db, final String iText) throws IOException {
    Object lastResult = null;
    int maxRetry = 1;

    context.setVariable("transactionRetries", 0);

    for (int retry = 0; retry < maxRetry; retry++) {
      try {
        int txBegunAtLine = -1;
        int txBegunAtPart = -1;
        lastResult = null;

        final BufferedReader reader = new BufferedReader(new StringReader(iText));

        int line = 0;
        int linePart = 0;
        String lastLine;
        boolean txBegun = false;

        for (; line < txBegunAtLine; ++line)
          // SKIP PREVIOUS COMMAND AND JUMP TO THE BEGIN IF ANY
          reader.readLine();

        for (; (lastLine = reader.readLine()) != null; ++line) {
          lastLine = lastLine.trim();

          final List<String> lineParts = OStringSerializerHelper.smartSplit(lastLine, ';');

          if (line == txBegunAtLine)
            // SKIP PREVIOUS COMMAND PART AND JUMP TO THE BEGIN IF ANY
            linePart = txBegunAtPart;
          else
            linePart = 0;

          for (; linePart < lineParts.size(); ++linePart) {
            final String lastCommand = lineParts.get(linePart);

            if (OStringSerializerHelper.startsWithIgnoreCase(lastCommand, "let ")) {
              final int equalsPos = lastCommand.indexOf('=');
              final String variable = lastCommand.substring("let ".length(), equalsPos).trim();
              final String cmd = lastCommand.substring(equalsPos + 1).trim();

              lastResult = db.command(new OCommandSQL(cmd).setContext(getContext())).execute();

              // PUT THE RESULT INTO THE CONTEXT
              getContext().setVariable(variable, lastResult);
            } else if ("begin".equalsIgnoreCase(lastCommand)) {

              if (txBegun)
                throw new OCommandSQLParsingException("Transaction already begun");

              txBegun = true;
              txBegunAtLine = line;
              txBegunAtPart = linePart;

              db.begin();

            } else if ("rollback".equalsIgnoreCase(lastCommand)) {

              if (!txBegun)
                throw new OCommandSQLParsingException("Transaction not begun");

              db.rollback();

              txBegun = false;
              txBegunAtLine = -1;
              txBegunAtPart = -1;

            } else if (OStringSerializerHelper.startsWithIgnoreCase(lastCommand, "commit")) {
              if (txBegunAtLine < 0)
                throw new OCommandSQLParsingException("Transaction not begun");

              if (retry == 0 && lastCommand.length() > "commit ".length()) {
                // FIRST CYCLE: PARSE RETRY TIMES OVERWRITING DEFAULT = 1
                String next = lastCommand.substring("commit ".length()).trim();
                if (OStringSerializerHelper.startsWithIgnoreCase(next, "retry ")) {
                  next = next.substring("retry ".length()).trim();
                  maxRetry = Integer.parseInt(next) + 1;
                }
              }

              db.commit();

              txBegun = false;
              txBegunAtLine = -1;
              txBegunAtPart = -1;

            } else if (OStringSerializerHelper.startsWithIgnoreCase(lastCommand, "sleep ")) {

              final String sleepTimeInMs = lastCommand.substring("sleep ".length()).trim();
              try {
                Thread.sleep(Integer.parseInt(sleepTimeInMs));
              } catch (InterruptedException e) {
              }

            } else if (OStringSerializerHelper.startsWithIgnoreCase(lastCommand, "return ")) {

              final String variable = lastCommand.substring("return ".length()).trim();

              if (variable.equalsIgnoreCase("NULL"))
                lastResult = null;
              else if (variable.startsWith("$"))
                lastResult = getContext().getVariable(variable);
              else if (variable.startsWith("[") && variable.endsWith("]")) {
                // ARRAY - COLLECTION
                final List<String> items = new ArrayList<String>();

                OStringSerializerHelper.getCollection(variable, 0, items);
                final List<Object> result = new ArrayList<Object>(items.size());

                for (int i = 0; i < items.size(); ++i) {
                  String item = items.get(i);

                  Object res;
                  if (item.startsWith("$"))
                    res = getContext().getVariable(item);
                  else
                    res = item;

                  if (OMultiValue.isMultiValue(res) && OMultiValue.getSize(res) == 1)
                    res = OMultiValue.getFirstValue(res);

                  result.add(res);
                }
                lastResult = result;
              } else if (variable.startsWith("{") && variable.endsWith("}")) {
                // MAP
                final Map<String, String> map = OStringSerializerHelper.getMap(variable);
                final Map<Object, Object> result = new HashMap<Object, Object>(map.size());

                for (Map.Entry<String, String> entry : map.entrySet()) {
                  // KEY
                  String stringKey = entry.getKey();
                  if (stringKey == null)
                    continue;

                  stringKey = stringKey.trim();

                  Object key;
                  if (stringKey.startsWith("$"))
                    key = getContext().getVariable(stringKey);
                  else
                    key = stringKey;

                  if (OMultiValue.isMultiValue(key) && OMultiValue.getSize(key) == 1)
                    key = OMultiValue.getFirstValue(key);

                  // VALUE
                  String stringValue = entry.getValue();
                  if (stringValue == null)
                    continue;

                  stringValue = stringValue.trim();

                  Object value;
                  if (stringValue.toString().startsWith("$"))
                    value = getContext().getVariable(stringValue);
                  else
                    value = stringValue;

                  if (OMultiValue.isMultiValue(value) && OMultiValue.getSize(value) == 1)
                    value = OMultiValue.getFirstValue(value);

                  result.put(key, value);
                }
                lastResult = result;
              } else
                lastResult = variable;

              // END OF THE SCRIPT
              return lastResult;

            } else if (lastCommand != null && lastCommand.length() > 0)
              lastResult = db.command(new OCommandSQL(lastCommand).setContext(getContext())).execute();
          }
        }
      } catch (OConcurrentModificationException e) {
        context.setVariable("retries", retry);
        getDatabase().getLocalCache().clear();
      }
    }

    return lastResult;
  }
}
