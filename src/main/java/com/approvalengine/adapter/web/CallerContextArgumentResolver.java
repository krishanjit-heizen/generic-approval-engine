package com.approvalengine.adapter.web;

import com.approvalengine.error.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Lets a controller declare {@code CallerContext} as a parameter and get the authenticated
 * caller injected.
 *
 * <p>Chosen over a request-scoped bean or a thread-local so that tenancy is <b>visible in
 * every controller signature</b>. Every consumer-facing handler takes a {@code CallerContext}
 * and passes its {@code appId} into the service layer; a handler that forgot to scope its
 * query would stand out in review, whereas an ambient thread-local makes that omission
 * invisible.
 */
@Component
public class CallerContextArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object context = request == null ? null : request.getAttribute(CallerContext.ATTRIBUTE);
        if (context == null) {
            // The filter populates this for every /api/** path, so a null here means a handler
            // was mapped outside the authenticated surface. Fail rather than fall back to an
            // unscoped context, which would silently read across tenants.
            throw new UnauthorizedException("api-key-required",
                    "no authenticated application on this request");
        }
        return context;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(this);
    }
}
