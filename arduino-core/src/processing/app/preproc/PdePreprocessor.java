/* -*- mode: jde; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  PdePreprocessor - wrapper for default ANTLR-generated parser
  Part of the Wiring project - http://wiring.org.co

  Copyright (c) 2004-05 Hernando Barragan

  Processing version Copyright (c) 2004-05 Ben Fry and Casey Reas
  Copyright (c) 2001-04 Massachusetts Institute of Technology

  ANTLR-generated parser and several supporting classes written
  by Dan Mosedale via funding from the Interaction Institute IVREA.

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app.preproc;

import static processing.app.I18n._;
import processing.app.*;
import processing.app.legacy.PApplet;

import java.io.*;
import java.util.*;
import java.util.regex.*;


/**
 * Class that orchestrates preprocessing p5 syntax into straight Java.
 */
public class PdePreprocessor {
  
  private static final String IMPORT_REGEX = "^\\s*#include\\s*[<\"](\\S+)[\">]";
  
  // stores number of built user-defined function prototypes
  public int prototypeCount = 0;

  // stores number of included library headers written
  // we always write one header: Arduino.h
  public int headerCount = 1;

  // the prototypes that are generated by the preprocessor
  List<String> prototypes;

  // these ones have the .* at the end, since a class name might be at the end
  // instead of .* which would make trouble other classes using this can lop
  // off the . and anything after it to produce a package name consistently.
  List<String> programImports;

  // imports just from the code folder, treated differently
  // than the others, since the imports are auto-generated.
  List<String> codeFolderImports;

  String program;


  /**
   * Setup a new preprocessor.
   */
  public PdePreprocessor() {
  }

  /**
   * Writes out the head of the c++ code generated for a sketch.
   * Called from processing.app.Sketch.
   * @param program the concatenated code from all tabs containing pde-files
   */
  public int writePrefix(String program)
      throws FileNotFoundException {
    // if the program ends with no CR or LF an OutOfMemoryError will happen.
    // not gonna track down the bug now, so here's a hack for it:
    // http://dev.processing.org/bugs/show_bug.cgi?id=5
    program += "\n";

    // if the program ends with an unterminated multi-line comment,
    // an OutOfMemoryError or NullPointerException will happen.
    // again, not gonna bother tracking this down, but here's a hack.
    // http://dev.processing.org/bugs/show_bug.cgi?id=16
    program = scrubComments(program);
    // If there are errors, an exception is thrown and this fxn exits.

    if (PreferencesData.getBoolean("preproc.substitute_unicode")) {
      program = substituteUnicode(program);
    }

    //String importRegexp = "(?:^|\\s|;)(import\\s+)(\\S+)(\\s*;)";
    programImports = new ArrayList<String>();

    String[][] pieces = PApplet.matchAll(program, IMPORT_REGEX);

    if (pieces != null)
      for (int i = 0; i < pieces.length; i++)
        programImports.add(pieces[i][1]);  // the package name

    codeFolderImports = new ArrayList<String>();
//    if (codeFolderPackages != null) {
//      for (String item : codeFolderPackages) {
//        codeFolderImports.add(item + ".*");
//      }
//    }

    prototypes = prototypes(program);

    // store # of prototypes so that line number reporting can be adjusted
    prototypeCount = prototypes.size();

    // do this after the program gets re-combobulated
    this.program = program;

    return headerCount + prototypeCount;
  }

  public static List<String> findIncludes(String code){
   
    String[][] pieces = PApplet.matchAll(code, IMPORT_REGEX);

    ArrayList programImports = new ArrayList<String>();
    
    if (pieces != null)
      for (int i = 0; i < pieces.length; i++)
        programImports.add(pieces[i][1]);  // the package name

    return programImports;
  }
    

  static String substituteUnicode(String program) {
    // check for non-ascii chars (these will be/must be in unicode format)
    char p[] = program.toCharArray();
    int unicodeCount = 0;
    for (int i = 0; i < p.length; i++) {
      if (p[i] > 127) unicodeCount++;
    }
    // if non-ascii chars are in there, convert to unicode escapes
    if (unicodeCount != 0) {
      // add unicodeCount * 5.. replacing each unicode char
      // with six digit uXXXX sequence (xxxx is in hex)
      // (except for nbsp chars which will be a replaced with a space)
      int index = 0;
      char p2[] = new char[p.length + unicodeCount*5];
      for (int i = 0; i < p.length; i++) {
        if (p[i] < 128) {
          p2[index++] = p[i];

        } else if (p[i] == 160) {  // unicode for non-breaking space
          p2[index++] = ' ';

        } else {
          int c = p[i];
          p2[index++] = '\\';
          p2[index++] = 'u';
          char str[] = Integer.toHexString(c).toCharArray();
          // add leading zeros, so that the length is 4
          //for (int i = 0; i < 4 - str.length; i++) p2[index++] = '0';
          for (int m = 0; m < 4 - str.length; m++) p2[index++] = '0';
          System.arraycopy(str, 0, p2, index, str.length);
          index += str.length;
        }
      }
      program = new String(p2, 0, index);
    }
    return program;
  }

  /**
   * preprocesses a pde file and writes out a cpp file into the specified
   * OutputStream
   *
   * @param output
   * @throws Exception
   */
  public void write(OutputStream output) throws Exception {
    PrintStream stream = new PrintStream(output);
    writeProgram(stream, program, prototypes);
    writeFooter(stream);
  }

  // Write the pde program to the cpp file
  protected void writeProgram(PrintStream out, String program, List<String> prototypes) {
    int prototypeInsertionPoint = firstStatement(program);

    out.print(program.substring(0, prototypeInsertionPoint));
    out.print("#include \"Arduino.h\"\n");

    // print user defined prototypes
    for (int i = 0; i < prototypes.size(); i++) {
      out.print(prototypes.get(i) + "\n");
    }
    String[] lines = program.substring(0, prototypeInsertionPoint).split("\n", -1);
    out.println("#line " + (lines.length - 1));
    out.print(program.substring(prototypeInsertionPoint));
  }


  /**
   * Write any necessary closing text.
   *
   * @param out         PrintStream to write it to.
   */
  protected void writeFooter(PrintStream out) throws java.lang.Exception {}


  public List<String> getExtraImports() {
    return programImports;
  }





  /**
   * Returns the index of the first character that's not whitespace, a comment
   * or a pre-processor directive.
   */
  public int firstStatement(String in) {
    // whitespace
    String p = "\\s+";

    // multi-line and single-line comment
    //p += "|" + "(//\\s*?$)|(/\\*\\s*?\\*/)";
    p += "|(/\\*[^*]*(?:\\*(?!/)[^*]*)*\\*/)|(//.*?$)";

    // pre-processor directive
    p += "|(#(?:\\\\\\n|.)*)";
    Pattern pattern = Pattern.compile(p, Pattern.MULTILINE);

    Matcher matcher = pattern.matcher(in);
    int i = 0;
    while (matcher.find()) {
      if (matcher.start()!=i)
        break;
      i = matcher.end();
    }

    return i;
  }

  /**
   * Strips comments, pre-processor directives, single- and double-quoted
   * strings from a string.
   * @param in the String to strip
   * @return the stripped String
   */
  public String strip(String in) {
    // XXX: doesn't properly handle special single-quoted characters
    // single-quoted character
    String p = "('.')";

    p += "|('\\\\\"')";

    // double-quoted string
    p += "|(\"(?:[^\"\\\\]|\\\\.)*\")";

    // single and multi-line comment
    //p += "|" + "(//\\s*?$)|(/\\*\\s*?\\*/)";
    p += "|(//.*?$)|(/\\*[^*]*(?:\\*(?!/)[^*]*)*\\*/)";

    // pre-processor directive
    p += "|" + "(^\\s*#.*?$)";

    StringBuilder sb = new StringBuilder(in);
    Pattern pattern = Pattern.compile(p, Pattern.MULTILINE | Pattern.DOTALL);
    Matcher matcher = pattern.matcher(sb);
    while (matcher.find()) {
      String replacement = composeReplacementString(new StringBuilder(sb.subSequence(matcher.start(), matcher.end())));
      sb.replace(matcher.start(), matcher.end(), replacement);
    }
    return sb.toString();
  }

  private String composeReplacementString(StringBuilder sb) {
    for (int i = 0; i < sb.length(); i++) {
      if (sb.charAt(i) != '\n') {
        sb.setCharAt(i, ' ');
      }
    }
    return sb.toString();
  }

  /**
   * Removes the contents of all top-level curly brace pairs {}.
   * @param in the String to collapse
   * @return the collapsed String
   */
  private String collapseBraces(String in) {
    StringBuffer buffer = new StringBuffer();
    int nesting = 0;
    int start = 0;

    // XXX: need to keep newlines inside braces so we can determine the line
    // number of a prototype
    for (int i = 0; i < in.length(); i++) {
      if (in.charAt(i) == '{') {
        if (nesting == 0) {
          buffer.append(in.substring(start, i + 1));  // include the '{'
        }
        nesting++;
      }
      if (in.charAt(i) == '}') {
        nesting--;
        if (nesting == 0) {
          start = i; // include the '}'
        }
      }
    }

    buffer.append(in.substring(start));

    return buffer.toString();
  }

  public ArrayList<String> prototypes(String in) {
    in = collapseBraces(strip(in));

    // XXX: doesn't handle ... varargs
    // XXX: doesn't handle function pointers
    Pattern prototypePattern = Pattern.compile("[\\w\\[\\]\\*]+\\s+[&\\[\\]\\*\\w\\s]+\\([&,\\[\\]\\*\\w\\s]*\\)(?=\\s*;)");
    Pattern functionPattern  = Pattern.compile("[\\w\\[\\]\\*]+\\s+[&\\[\\]\\*\\w\\s]+\\([&,\\[\\]\\*\\w\\s]*\\)(?=\\s*\\{)");

    // Find already declared prototypes
    ArrayList<String> prototypeMatches = new ArrayList<String>();
    Matcher prototypeMatcher = prototypePattern.matcher(in);
    while (prototypeMatcher.find())
      prototypeMatches.add(prototypeMatcher.group(0) + ";");

    // Find all functions and generate prototypes for them
    ArrayList<String> functionMatches = new ArrayList<String>();
    Matcher functionMatcher = functionPattern.matcher(in);
    while (functionMatcher.find())
      functionMatches.add(functionMatcher.group(0) + ";");

    // Remove generated prototypes that exactly match ones found in the source file
    for (int functionIndex=functionMatches.size() - 1; functionIndex >= 0; functionIndex--) {
      for (int prototypeIndex=0; prototypeIndex < prototypeMatches.size(); prototypeIndex++) {
        if ((functionMatches.get(functionIndex)).equals(prototypeMatches.get(prototypeIndex))) {
          functionMatches.remove(functionIndex);
          break;
        }
      }
    }

    return functionMatches;
  }

  private boolean isStartOrEndOfString(char p[], int index) {
    if (p[index] != '\"') {
      return false;
    }

    if (index == 0) {
      return true;
    }

    if (p[index - 1] == '\\') {
      return false;
    }

    if (index - 2 >= 0 && p[index - 2] == '\\') {
      return true;
    }

    return true;
  }

  /**
   * Replace all commented portions of a given String as spaces.
   * Utility function used here and in the preprocessor.
   */
  public String scrubComments(String what) {
    char p[] = what.toCharArray();

    int index = 0;
    boolean insideString = false;
    while (index < p.length) {
      if (isStartOrEndOfString(p, index)) {
        insideString = !insideString;
      }
      // for any double slash comments, ignore until the end of the line
      if (!insideString && (p[index] == '/') &&
          (index < p.length - 1) &&
          (p[index+1] == '/')) {
        p[index++] = ' ';
        p[index++] = ' ';
        while ((index < p.length) &&
               (p[index] != '\n')) {
          p[index++] = ' ';
        }

        // check to see if this is the start of a new multiline comment.
        // if it is, then make sure it's actually terminated somewhere.
      } else if (!insideString && (p[index] == '/') &&
                 (index < p.length - 1) &&
                 (p[index+1] == '*')) {
        p[index++] = ' ';
        p[index++] = ' ';
        boolean endOfRainbow = false;
        while (index < p.length - 1) {
          if ((p[index] == '*') && (p[index+1] == '/')) {
            p[index++] = ' ';
            p[index++] = ' ';
            endOfRainbow = true;
            break;

          } else {
            // continue blanking this area
            if (p[index] != '\n') {
              p[index] = ' ';
            }
            index++;
          }
        }
        if (!endOfRainbow) {
          throw new RuntimeException(_("Missing the */ from the end of a " +
                                       "/* comment */"));
        }
      } else {  // any old character, move along
        index++;
      }
    }
    return new String(p);
  }
}
