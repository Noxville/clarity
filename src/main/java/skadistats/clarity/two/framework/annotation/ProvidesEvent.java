package skadistats.clarity.two.framework.annotation;

import org.atteo.classindex.IndexAnnotated;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(value = ElementType.TYPE)
@IndexAnnotated
public @interface ProvidesEvent {
    Class<? extends Annotation>[] value();
}