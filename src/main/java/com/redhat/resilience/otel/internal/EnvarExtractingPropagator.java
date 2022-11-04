/*
 * Copyright Red Hat
 * SPDX-License-Identifier: Apache-2.0
 */

package com.redhat.resilience.otel.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

import static com.redhat.resilience.otel.internal.OtelContextUtil.extractContextFromTraceParent;
import static com.redhat.resilience.otel.internal.OtelContextUtil.extractTraceState;

/**
 * {@link TextMapPropagator} implementation (for propagating trace context) that consumes environment variables during
 * extraction, and injects W3C headers for downstream calls. This is intended to be used in command-line tooling,
 * and is intended to be compatible with the Jenkins Opentelemetry Plugin.
 * <p>
 * This <b>ONLY</b> works with single-execution tools; if your software uses a processing loop and runs as a daemon of
 * some sort, <b>DO NOT USE THIS.</b>
 * <p>
 * This also uses {@link W3CTraceContextPropagator} to inject trace state into downstream calls.
 * <p>
 * See also: https://github.com/jenkinsci/opentelemetry-plugin/blob/master/docs/job-traces.md#environment-variables-for-trace-context-propagation-and-integrations
 */
public class EnvarExtractingPropagator
                implements TextMapPropagator
{
    private static final String ENVAR_TRACE_PARENT = "TRACEPARENT";

    private static final String ENVAR_TRACE_STATE = "TRACESTATE";

    private static final String ENVAR_TRACE_ID = "TRACE_ID";

    private static final String ENVAR_SPAN_ID = "SPAN_ID";

    private static final EnvarExtractingPropagator INSTANCE = new EnvarExtractingPropagator();

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private EnvarExtractingPropagator()
    {
    }

    /**
     * Return the singleton instance
     * @return the singleton
     */
    public static EnvarExtractingPropagator getInstance()
    {
        return INSTANCE;
    }

    /**
     * Return the set of fields we will inject. These are intended to be fully compatible with the W3C trace context, so
     * it delegates directly to {@link W3CTraceContextPropagator#fields()}
     */
    @Override
    public Collection<String> fields()
    {
        return W3CTraceContextPropagator.getInstance().fields();
    }

    /**
     * Delegate to {@link W3CTraceContextPropagator#inject(Context, Object, TextMapSetter)} for field injection in the
     * outgoing context.
     *
     * @param context The context to inject into
     * @param c The instance that contains the tracing context
     * @param textMapSetter The setter that can take tracing context from the instance and inject it into the context
     * @param <C> The instance type
     */
    @Override
    public <C> void inject( Context context, C c, TextMapSetter<C> textMapSetter )
    {
        W3CTraceContextPropagator.getInstance().inject( context, c, textMapSetter );
    }

    /**
     * Extract trace context from system environment variables. This <b>ONLY</b> works with single-execution tools;
     * if your system uses a processing loop and runs as a daemon of some sort, <b>DO NOT USE THIS.</b>
     * <p>
     * This extraction will reuse the TRACEPARENT, TRACESTATE, TRACE_ID, and SPAN_ID environment variables produced
     * by Jenkins via the Jenkins Opentelemetry Plugin. It will <b>prefer</b> TRACEPARENT then fall back to
     * [TRACE_ID + SPAN_ID]. If it can establish a basic trace context from those, it will look for TRACESTATE to
     * augment the base context information.
     *
     * @param context The context to set extracted trace context into
     * @param carrier The instance from which to extract context
     * @param getter The method for retrieving trace context from the instance
     * @param <C> The type of the instance
     * @return The updated trace context object
     */
    @Override
    public <C> Context extract( Context context, C carrier, TextMapGetter<C> getter )
    {
        if ( context == null )
        {
            context = Context.root();
        }

        SpanContext spanContext = extractFromEnvars();
        if ( !spanContext.isValid() )
        {
            return context;
        }

        return context.with( Span.wrap( spanContext ) );
    }

    /**
     * Read the system environment variables looking for the trace context. If found, return the SpanContext built from
     * those variables.
     *
     * @param <C> The instance type; disregarded here
     * @return The {@link SpanContext} or {@link SpanContext#getInvalid()} one if no environment variables are found.
     */
    private static <C> SpanContext extractFromEnvars()
    {
        final Logger logger = LoggerFactory.getLogger( OtelContextUtil.class );

        Map<String, String> envMap = System.getenv();

        SpanContext contextFromParent = null;

        String traceParentValue = envMap.get( ENVAR_TRACE_PARENT );
        logger.info("Trace parent: {}", traceParentValue);
        if ( traceParentValue != null )
        {
            contextFromParent = extractContextFromTraceParent( traceParentValue );
        }

        if ( contextFromParent == null )
        {
            String traceId = envMap.get( ENVAR_TRACE_ID );
            String parentSpanId = envMap.get( ENVAR_SPAN_ID );
            logger.debug("Trace ID: {}, Span ID: {}", traceId, parentSpanId);
            if ( traceId != null && !traceId.isEmpty() && parentSpanId != null && !parentSpanId.isEmpty() )
            {
                contextFromParent = SpanContext.createFromRemoteParent( traceId, parentSpanId, TraceFlags.getDefault(),
                                                                        TraceState.getDefault() );
            }
        }

        if ( contextFromParent == null )
        {
            return SpanContext.getInvalid();
        }
        else if ( !contextFromParent.isValid() )
        {
            return contextFromParent;
        }

        String traceStateValue = envMap.get( ENVAR_TRACE_STATE );
        logger.debug("Trace state: {}", traceStateValue);
        if ( traceStateValue == null || traceStateValue.isEmpty() )
        {
            return contextFromParent;
        }

        try
        {
            TraceState traceState = extractTraceState( traceStateValue );
            return SpanContext.createFromRemoteParent( contextFromParent.getTraceId(), contextFromParent.getSpanId(),
                                                       contextFromParent.getTraceFlags(), traceState );
        }
        catch ( IllegalArgumentException e )
        {
            logger.debug( "Unparseable tracestate header. Returning span context without state." );
            return contextFromParent;
        }
    }
}
