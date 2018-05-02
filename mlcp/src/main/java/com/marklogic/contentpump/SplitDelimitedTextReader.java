/*
 * Copyright 2003-2018 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.contentpump;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import com.marklogic.contentpump.utilities.CSVParserFormatter;
import com.marklogic.contentpump.utilities.DelimitedSplit;
import com.marklogic.contentpump.utilities.IdGenerator;
import com.marklogic.mapreduce.utilities.TextArrayWritable;

/**
 * Reader for DelimitedTextInputFormat if split_input is true
 *
 * @author ali
 *
 * @param <VALUEIN>
 */
public class SplitDelimitedTextReader<VALUEIN> extends
    DelimitedTextReader<VALUEIN> {
    public static final Log LOG = LogFactory
        .getLog(SplitDelimitedTextReader.class);
    private long start;
    private long end;
    private String lineSeparator;

    @Override
    public void initialize(InputSplit inSplit, TaskAttemptContext context)
        throws IOException, InterruptedException {
        initConfig(context);
        initDocType();
        initDelimConf();
        setFile(((FileSplit) inSplit).getPath());
        fs = file.getFileSystem(context.getConfiguration());
        start = ((DelimitedSplit) inSplit).getStart();
        end = start + ((DelimitedSplit) inSplit).getLength();
        initParser(inSplit);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean nextKeyValue() throws IOException, InterruptedException {
        if (parser == null || parserIterator == null) {
            return false;
        }
        try {
            if (!parserIterator.hasNext()) {
        		bytesRead = fileLen;
                return false;
            }
            CSVRecord record = getRecordLine();
            if (record.getCharacterByte() >= end) {
                return false;
            }
            String[] values = getLine(record);
            if (values.length != fields.length) {
                setSkipKey(0, 0,
                        "number of fields does not match number of columns");
                return true;
            }
            docBuilder.newDoc();
            for (int i = 0; i < fields.length; i++) {
                // skip empty column in header generated by trailing delimiter
                if (fields[i].equals(""))
                    continue;
                if (!generateId && uriId == i) {
                    if (setKey(values[i], 0, 0, true)) {
                        return true;
                    }
                }
                try {
                    docBuilder.put(fields[i], values[i]);
                } catch (Exception e) {
                    setSkipKey(0, 0, e.getMessage());
                    return true;
                }
            }
            docBuilder.build();
            if (generateId) {
                if (setKey(idGen.incrementAndGet(), 0, 0, true)) {
                    return true;
                }
            }
            if (value instanceof Text) {
                ((Text)value).set(docBuilder.getDoc());
            } else {
                ((Text)((ContentWithFileNameWritable<VALUEIN>)
                        value).getValue()).set(docBuilder.getDoc());
            }
        } catch (RuntimeException ex) {
            if (ex.getMessage().contains(
                "invalid char between encapsulated token and delimiter")) {
                setSkipKey(0, 0,
                        "invalid char between encapsulated token and delimiter");
                // hasNext() will always be true here since this exception is caught
                if (parserIterator.hasNext()) {
                	// consume the rest fields of this line
                	parserIterator.next();
                }
            } else {
                throw ex;
            }
        }
        return true;
    }

    @Override
    protected void initParser(InputSplit inSplit) throws IOException,
        InterruptedException {
        fileIn = openFile(inSplit, true);
        if (fileIn == null) {
            return;
        }
        // get header from the DelimitedSplit
        TextArrayWritable taw = ((DelimitedSplit) inSplit).getHeader();
        fields = taw.toStrings();
        try {
            docBuilder.configFields(conf, fields);
        } catch (IllegalArgumentException e) {
            LOG.error("Skipped file: " + file.toUri()
                    + ", reason: " + e.getMessage());
            return;
        }

        lineSeparator = retrieveLineSeparator(fileIn);
        if (start != 0) {
            // in case the cut point is \n, back off 1 char to create a partial
            // line so that 1st line can be skipped
            start--;
        }

        fileIn.seek(start);

        instream = new InputStreamReader(fileIn, encoding);

        bytesRead = 0;
        fileLen = inSplit.getLength();
        if (uriName == null) {
            generateId = conf.getBoolean(CONF_INPUT_GENERATE_URI, false);
            if (generateId) {
                idGen = new IdGenerator(file.toUri().getPath() + "-"
                    + ((FileSplit) inSplit).getStart());
            } else {
                uriId = 0;
            }
        }

        boolean found = generateId || uriId == 0;

        for (int i = 0; i < fields.length && !found; i++) {
            if (fields[i].equals(uriName)) {
                uriId = i;
                found = true;
                break;
            }
        }
        if (found == false) {
            // idname doesn't match any columns
            LOG.error("Skipped file: " + file.toUri()
                    + ", reason: " + URI_ID + " " + uriName
                    + " is not found");
            return;
        }

        // keep leading and trailing whitespaces to ensure accuracy of pos
        // do not skip empty line just in case the split boundary is \n.
        // Set encapsulator to null so that it will ignore quotes
        // while parsing the first line in the split
        parser = new CSVParser(instream, CSVParserFormatter.
                getFormat(delimiter, null, false,false),
                start, 0L, encoding);
        parserIterator = parser.iterator();

        if (parserIterator.hasNext()) {
            // skip first line:
            // 1st split, skip header; other splits, skip partial line
            getLine();
            // Read next record and get the beginning position,
            // which will be used to initialize the parser
            if (parserIterator.hasNext()) {
                CSVRecord record = getRecordLine();
                long pos = record.getCharacterByte();
                fileIn.seek(pos);
                instream = new InputStreamReader(fileIn, encoding);
                parser = new CSVParser(instream, CSVParserFormatter.
                        getFormat(delimiter, encapsulator, false,false),
                        pos, 0L, encoding);
                parserIterator = parser.iterator();
            }

        }
    }

    private String retrieveLineSeparator(FSDataInputStream fis)
        throws IOException {
        char current;
        String lineSeparator = "";
        while (fis.available() > 0) {
            current = (char) fis.read();
            if ((current == '\n') || (current == '\r')) {
                lineSeparator += current;
                if (fis.available() > 0) {
                    char next = (char) fis.read();
                    if ((next == '\r') || (next == '\n')) {
                        lineSeparator += next;
                    }
                }
                return lineSeparator;
            }
        }
        return null;
    }

    protected String convertToLine(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i == values.length - 1 && values[i].trim().equals("")) {
                sb.append(lineSeparator);
                return sb.toString();
            }
            String s = values[i];
            sb.append(s);
            sb.append(delimiter);
        }
        sb.replace(sb.length() - 1, sb.length(), lineSeparator);
        return sb.toString();
    }

}
