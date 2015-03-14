package skadistats.clarity.two.framework.invocation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public class EventListener<A extends Annotation> extends AbstractInvocationPoint<A> {

    public EventListener(A annotation, Class<?> processorClass, Method method, int arity) {
        super(annotation, processorClass, method, arity);
    }

    public Class<? extends Annotation> getEventClass() {
        return annotation.annotationType();
    }



}