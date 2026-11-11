package cc.projectargus.workbench.config;

import cc.projectargus.workbench.web.dto.ApiErrorDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces an explicit upper bound ({@code ARGUS_MAX_REQUEST_BODY_BYTES}) on incoming JSON request
 * bodies before Jackson deserializes payloads into application DTOs.
 *
 * <p>Handles both declared {@code Content-Length} headers and unknown/chunked transfer-encoding
 * streams via a byte-counting {@link ServletInputStream}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JsonRequestBodySizeFilter extends OncePerRequestFilter {

    private final ArgusWorkbenchProperties properties;
    private final ObjectMapper objectMapper;

    public JsonRequestBodySizeFilter(
        ArgusWorkbenchProperties properties,
        ObjectMapper objectMapper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long maxBytes = Math.max(512L, properties.getMaxRequestBodyBytes());
        long declaredLength = request.getContentLengthLong();

        if (declaredLength > maxBytes) {
            writePayloadTooLarge(response, maxBytes);
            return;
        }

        HttpServletRequest boundedRequest = new BoundedBodyRequestWrapper(request, maxBytes);
        try {
            filterChain.doFilter(boundedRequest, response);
        } catch (RequestBodyTooLargeException tooLarge) {
            if (!response.isCommitted()) {
                writePayloadTooLarge(response, tooLarge.getMaxBytes());
            }
        } catch (ServletException | RuntimeException ex) {
            RequestBodyTooLargeException cause = findTooLargeCause(ex);
            if (cause != null && !response.isCommitted()) {
                writePayloadTooLarge(response, cause.getMaxBytes());
                return;
            }
            throw ex;
        }
    }

    private void writePayloadTooLarge(HttpServletResponse response, long maxBytes) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiErrorDto error = new ApiErrorDto(
            "payload_too_large",
            "Request body exceeds the maximum allowed size of " + maxBytes + " bytes."
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
        response.flushBuffer();
    }

    public static RequestBodyTooLargeException findTooLargeCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (current instanceof RequestBodyTooLargeException target) {
                return target;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static final class BoundedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream wrappedStream;
        private BufferedReader wrappedReader;

        BoundedBodyRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (wrappedReader != null) {
                throw new IllegalStateException("getReader() has already been called on this request");
            }
            if (wrappedStream == null) {
                wrappedStream = new BoundedServletInputStream(super.getInputStream(), maxBytes);
            }
            return wrappedStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (wrappedStream != null) {
                throw new IllegalStateException("getInputStream() has already been called on this request");
            }
            if (wrappedReader == null) {
                wrappedReader = new BufferedReader(
                    new InputStreamReader(
                        new BoundedServletInputStream(super.getInputStream(), maxBytes),
                        StandardCharsets.UTF_8
                    )
                );
            }
            return wrappedReader;
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead = 0L;

        BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                incrementAndCheck(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) {
                incrementAndCheck(n);
            }
            return n;
        }

        private void incrementAndCheck(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
