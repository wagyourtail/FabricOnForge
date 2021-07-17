package net.fabricmc.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface EnvironmentInterfaces {
    /**
     * Returns the {@link EnvironmentInterface} annotations it holds.
     */
    EnvironmentInterface[] value();
}
