/*
 * Copyright (c) 2016.  All rights reserved. Lai Jiewen <alanljw12345@gmail,com
 */

package me.ctknight.uploadmanager.util.okhttputil;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.internal.Util;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import okio.Okio;
import okio.Source;

public class CountingInputStreamMultipartBody extends RequestBody implements CountingForwardBufferedSink.CountingCallback {
    //this a copy of MultipartBody, nothing but create() was modified in order to support

    /**
     * The "mixed" subtype of "multipart" is intended for use when the body parts are independent
     * and
     * need to be bundled in a particular order. Any "multipart" subtypes that an implementation
     * does
     * not recognize must be treated as being of subtype "mixed".
     */
    public static final MediaType MIXED = MediaType.parse("multipart/mixed");

    /**
     * The "multipart/alternative" type is syntactically identical to "multipart/mixed", but the
     * semantics are different. In particular, each of the body parts is an "alternative" version
     * of
     * the same information.
     */
    public static final MediaType ALTERNATIVE = MediaType.parse("multipart/alternative");

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In
     * particular, in a digest, the default {@code Content-Type} value for a body part is changed
     * from
     * "text/plain" to "message/rfc822".
     */
    public static final MediaType DIGEST = MediaType.parse("multipart/digest");

    /**
     * This type is syntactically identical to "multipart/mixed", but the semantics are different.
     * In
     * particular, in a parallel entity, the order of body parts is not significant.
     */
    public static final MediaType PARALLEL = MediaType.parse("multipart/parallel");

    /**
     * The media-type multipart/form-data follows the rules of all multipart MIME data streams as
     * outlined in RFC 2046. In forms, there are a series of fields to be supplied by the user who
     * fills out the form. Each field has a name. Within a given form, the names are unique.
     */
    public static final MediaType FORM = MediaType.parse("multipart/form-data");

    private static final byte[] COLONSPACE = {':', ' '};
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] DASHDASH = {'-', '-'};
    private static final int SEGMENT_SIZE = 2048;
    private final ByteString boundary;
    private final MediaType originalType;
    private final MediaType contentType;
    private final List<Part> parts;
    private final ProgressListener listener;
    private long contentLength = -1L;

    CountingInputStreamMultipartBody(ByteString boundary, MediaType type, List<Part> parts, ProgressListener listener) {
        this.boundary = boundary;
        this.originalType = type;
        this.contentType = MediaType.parse(type + "; boundary=" + boundary.utf8());
        this.parts = Util.immutableList(parts);
        this.listener = listener;
    }

    public static RequestBody create(final MediaType contentType, final InputStream in) {
        if (in == null) throw new NullPointerException("InputStream == null");

        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentLength() throws IOException {
                return in.available();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                Source source = null;
                try {
                    source = Okio.source(in);

                    int chunkSize = 2048;
                    long total = contentLength();
                    long current = 0;
                    for (; ; ) {
                        if (total == current) {
                            break;
                        }
                        long nextChunkSize = total - current < chunkSize ?
                                total - current : chunkSize;
                        sink.write(source, nextChunkSize);
                        current += nextChunkSize;
                    }
                } finally {
                    Util.closeQuietly(source);
                }
            }
        };
    }

    /**
     * Appends a quoted-string to a StringBuilder.
     *
     * <p>RFC 2388 is rather vague about how one should escape special characters in form-data
     * parameters, and as it turns out Firefox and Chrome actually do rather different things, and
     * both say in their comments that they're not really sure what the right approach is. We go
     * with
     * Chrome's behavior (which also experimentally seems to match what IE does), but if you
     * actually
     * want to have a good chance of things working, please avoid double-quotes, newlines, percent
     * signs, and the like in your field names.
     */
    public static StringBuilder appendQuotedString(StringBuilder target, String key) {
        target.append('"');
        for (int i = 0, len = key.length(); i < len; i++) {
            char ch = key.charAt(i);
            switch (ch) {
                case '\n':
                    target.append("%0A");
                    break;
                case '\r':
                    target.append("%0D");
                    break;
                case '"':
                    target.append("%22");
                    break;
                default:
                    target.append(ch);
                    break;
            }
        }
        target.append('"');
        return target;
    }

    public MediaType type() {
        return originalType;
    }

    public String boundary() {
        return boundary.utf8();
    }

    /** The number of parts in this multipart body. */
    public int size() {
        return parts.size();
    }

    public List<Part> parts() {
        return parts;
    }

    public Part part(int index) {
        return parts.get(index);
    }

    /** A combination of {@link #type()} and {@link #boundary()}. */
    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() throws IOException {
        long result = contentLength;
        if (result != -1L) return result;
        return contentLength = writeOrCountBytesViaDelegate(null, true);
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        writeOrCountBytesViaDelegate(sink, false);
    }

    /**
     * Either writes this request to {@code sink} or measures its content length. We have one
     * method
     * do double-duty to make sure the counting and content are consistent, particularly when it
     * comes
     * to awkward operations like measuring the encoded length of header strings, or the
     * length-in-digits of an encoded integer.
     */
    private long writeOrCountBytes(BufferedSink sink, boolean countBytes) throws IOException {
        long byteCount = 0L;

        Buffer byteCountBuffer = null;
        if (countBytes) {
            sink = byteCountBuffer = new Buffer();
        }

        for (int p = 0, partCount = parts.size(); p < partCount; p++) {
            Part part = parts.get(p);
            Headers headers = part.headers;
            RequestBody body = part.body;

            sink.write(DASHDASH);
            sink.write(boundary);
            sink.write(CRLF);

            if (headers != null) {
                for (int h = 0, headerCount = headers.size(); h < headerCount; h++) {
                    sink.writeUtf8(headers.name(h))
                            .write(COLONSPACE)
                            .writeUtf8(headers.value(h))
                            .write(CRLF);
                }
            }

            MediaType contentType = body.contentType();
            if (contentType != null) {
                sink.writeUtf8("Content-Type: ")
                        .writeUtf8(contentType.toString())
                        .write(CRLF);
            }

            long contentLength = body.contentLength();
            if (contentLength != -1) {
                sink.writeUtf8("Content-Length: ")
                        .writeDecimalLong(contentLength)
                        .write(CRLF);
            } else if (countBytes) {
                // We can't measure the body's size without the sizes of its components.
                byteCountBuffer.clear();
                return -1L;
            }

            sink.write(CRLF);

            if (countBytes) {
                byteCount += contentLength;
            } else {
                body.writeTo(sink);
            }

            sink.write(CRLF);
        }

        sink.write(DASHDASH);
        sink.write(boundary);
        sink.write(DASHDASH);
        sink.write(CRLF);

        if (countBytes) {
            byteCount += byteCountBuffer.size();
            byteCountBuffer.clear();
        }

        return byteCount;
    }

    private long writeOrCountBytesViaDelegate(BufferedSink delegate, boolean countByte) throws IOException {
        CountingForwardBufferedSink sinkWrapper = new CountingForwardBufferedSink(delegate, this);
        return writeOrCountBytes(sinkWrapper, countByte);
    }

    @Override
    public void notifyWritten(long written) {
        listener.transferred(written);
    }

    public interface ProgressListener {
        void transferred(long num);
    }

    public static final class Part {
        private final Headers headers;
        private final RequestBody body;

        private Part(Headers headers, RequestBody body) {
            this.headers = headers;
            this.body = body;
        }

        public static Part create(RequestBody body) {
            return create(null, body);
        }

        public static Part create(Headers headers, RequestBody body) {
            if (body == null) {
                throw new NullPointerException("body == null");
            }
            if (headers != null && headers.get("Content-Type") != null) {
                throw new IllegalArgumentException("Unexpected header: Content-Type");
            }
            if (headers != null && headers.get("Content-Length") != null) {
                throw new IllegalArgumentException("Unexpected header: Content-Length");
            }
            return new Part(headers, body);
        }

        public static Part createFormData(String name, String value) {
            return createFormData(name, null, RequestBody.create(null, value));
        }

        public static Part createFormData(String name, String filename, RequestBody body) {
            if (name == null) {
                throw new NullPointerException("name == null");
            }
            StringBuilder disposition = new StringBuilder("form-data; name=");
            appendQuotedString(disposition, name);

            if (filename != null) {
                disposition.append("; filename=");
                appendQuotedString(disposition, filename);
            }

            return create(Headers.of("Content-Disposition", disposition.toString()), body);
        }
    }

    public static final class Builder {
        private final ByteString boundary;
        private final List<Part> parts = new ArrayList<>();
        private MediaType type = MIXED;
        private ProgressListener listener;

        public Builder() {
            this(UUID.randomUUID().toString());
        }

        public Builder(String boundary) {
            this.boundary = ByteString.encodeUtf8(boundary);
        }

        /**
         * Set the MIME type. Expected values for {@code type} are {@link #MIXED} (the default),
         * {@link
         * #ALTERNATIVE}, {@link #DIGEST}, {@link #PARALLEL} and {@link #FORM}.
         */
        public Builder setType(MediaType type) {
            if (type == null) {
                throw new NullPointerException("type == null");
            }
            if (!type.type().equals("multipart")) {
                throw new IllegalArgumentException("multipart != " + type);
            }
            this.type = type;
            return this;
        }

        public Builder setProgressListener(ProgressListener listener) {
            this.listener = listener;
            return this;
        }

        /** Add a part to the body. */
        public Builder addPart(RequestBody body) {
            return addPart(Part.create(body));
        }

        /** Add a part to the body. */
        public Builder addPart(Headers headers, RequestBody body) {
            return addPart(Part.create(headers, body));
        }

        /** Add a form data part to the body. */
        public Builder addFormDataPart(String name, String value) {
            return addPart(Part.createFormData(name, value));
        }

        /** Add a form data part to the body. */
        public Builder addFormDataPart(String name, String filename, RequestBody body) {
            return addPart(Part.createFormData(name, filename, body));
        }

        /** Add a part to the body. */
        public Builder addPart(Part part) {
            if (part == null) throw new NullPointerException("part == null");
            parts.add(part);
            return this;
        }

        /** Assemble the specified parts into a request body. */
        public CountingInputStreamMultipartBody build() {
            if (parts.isEmpty()) {
                throw new IllegalStateException("Multipart body must have at least one part.");
            }
            return new CountingInputStreamMultipartBody(boundary, type, parts, listener);
        }
    }

}
