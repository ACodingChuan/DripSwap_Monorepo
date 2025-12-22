import { diag, DiagConsoleLogger, DiagLogLevel } from '@opentelemetry/api';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';

let started = false;

export function initOtel(): void {
  if (started) {
    return;
  }

  const enabled = import.meta.env.VITE_OTEL_ENABLED === 'true';
  if (!enabled) {
    return;
  }

  started = true;

  if (import.meta.env.DEV && import.meta.env.VITE_OTEL_DIAG_LOG_LEVEL === 'debug') {
    diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.DEBUG);
  }

  const serviceName = import.meta.env.VITE_OTEL_SERVICE_NAME ?? 'dripswap-frontend';
  const exporterUrl = resolveExporterUrl(import.meta.env.VITE_OTEL_EXPORTER_OTLP_ENDPOINT);
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? '';

  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({
      [SemanticResourceAttributes.SERVICE_NAME]: serviceName,
    }),
    spanProcessors: [
      new BatchSpanProcessor(
        new OTLPTraceExporter({
          url: exporterUrl,
        }),
      ),
    ],
  });

  provider.register();

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        propagateTraceHeaderCorsUrls: buildCorsUrlAllowList(apiBaseUrl),
        ignoreUrls: [/\/otlp\/v1\/traces$/, /\/v1\/traces$/],
        clearTimingResources: true,
      }),
    ],
  });
}

function resolveExporterUrl(configured?: string): string {
  const value = (configured ?? '').trim();
  if (value) {
    return value;
  }

  if (typeof window === 'undefined') {
    return '';
  }

  return new URL('/otlp/v1/traces', window.location.origin).toString();
}

function buildCorsUrlAllowList(apiBaseUrl: string): Array<string | RegExp> {
  const trimmed = apiBaseUrl.trim();
  if (!trimmed) {
    return [];
  }

  return [new RegExp(`^${escapeRegExp(trimmed)}`)];
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
