/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.features.opentracing;

import brave.Tracing;
import brave.baggage.BaggagePropagation;
import brave.propagation.Propagation;
import brave.propagation.Propagation.Getter;
import brave.propagation.Propagation.Setter;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BraveTracer implements Tracer {
  static BraveTracer wrap(brave.Tracing tracing) {
    if (tracing == null) throw new NullPointerException("tracing == null");
    return new BraveTracer(tracing);
  }

  final Tracing tracing;
  final brave.Tracer tracer;
  final TraceContext.Injector<TextMap> injector;
  final Extractor<TextMap> extractor;

  BraveTracer(brave.Tracing tracing) {
    this.tracing = tracing;
    tracer = tracing.tracer();
    injector = tracing.propagation().injector(TEXT_MAP_SETTER);
    Set<String> lcPropagationKeys = new LinkedHashSet<>();
    for (String keyName : BaggagePropagation.allKeyNames(tracing.propagation())) {
      lcPropagationKeys.add(keyName.toLowerCase(Locale.ROOT));
    }
    extractor = new TextMapExtractorAdaptor(tracing.propagation(), lcPropagationKeys);
  }

  @Override public ScopeManager scopeManager() {
    return null; // out-of-scope for a simple example
  }

  @Override public Span activeSpan() {
    return null; // out-of-scope for a simple example
  }

  @Override public Scope activateSpan(Span span) {
    return null; // out-of-scope for a simple example
  }

  @Override public BraveSpanBuilder buildSpan(String operationName) {
    return new BraveSpanBuilder(tracer, operationName);
  }

  @Override public <R> void inject(SpanContext spanContext, Format<R> format, R request) {
    if (format != Format.Builtin.HTTP_HEADERS) {
      throw new UnsupportedOperationException(format + " != Format.Builtin.HTTP_HEADERS");
    }
    TraceContext traceContext = ((BraveSpanContext) spanContext).context;
    injector.inject(traceContext, (TextMap) request);
  }

  @Override public <R> BraveSpanContext extract(Format<R> format, R request) {
    if (format != Format.Builtin.HTTP_HEADERS) {
      throw new UnsupportedOperationException(format.toString());
    }
    TraceContextOrSamplingFlags extractionResult = extractor.extract((TextMap) request);
    return BraveSpanContext.create(extractionResult);
  }

  @Override public void close() {
    tracing.close();
  }

  static final Setter<TextMap, String> TEXT_MAP_SETTER = new Setter<TextMap, String>() {
    @Override public void put(TextMap request, String key, String value) {
      request.put(key, value);
    }

    @Override public String toString() {
      return "TextMap::put";
    }
  };

  static final Getter<Map<String, String>, String> LC_MAP_GETTER =
    new Getter<Map<String, String>, String>() {
      @Override public String get(Map<String, String> request, String key) {
        return request.get(key.toLowerCase(Locale.ROOT));
      }

      @Override public String toString() {
        return "Map::getLowerCase";
      }
    };

  /**
   * Eventhough TextMap is named like Map, it doesn't have a retrieve-by-key method.
   *
   * <p>See https://github.com/opentracing/opentracing-java/issues/305
   */
  static final class TextMapExtractorAdaptor implements Extractor<TextMap> {
    final Set<String> lcPropagationKeys;
    final Extractor<Map<String, String>> delegate;

    TextMapExtractorAdaptor(Propagation<String> propagation, Set<String> lcPropagationKeys) {
      this.lcPropagationKeys = lcPropagationKeys;
      this.delegate = propagation.extractor(LC_MAP_GETTER);
    }

    /** Performs case-insensitive lookup */
    @Override public TraceContextOrSamplingFlags extract(TextMap entries) {
      Map<String, String> cache = new LinkedHashMap<>(); // Cache only the headers we would lookup
      for (Iterator<Map.Entry<String, String>> it = entries.iterator(); it.hasNext(); ) {
        Map.Entry<String, String> next = it.next();
        String inputKey = next.getKey().toLowerCase(Locale.ROOT);
        if (lcPropagationKeys.contains(inputKey)) {
          cache.put(inputKey, next.getValue());
        }
      }
      return delegate.extract(cache);
    }
  }
}
