package com.healthrx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies the production build packages the compiled frontend into the boot jar's static
 * resources (the single-artifact contract). Runs after the `classes` task, which depends on
 * copyFrontend -> :frontend:viteBuild.
 */
class AssetPackagingTest {

    @Test
    void frontendIndexIsOnTheClasspath() {
        assertThat(new ClassPathResource("static/index.html").exists())
                .as("frontend index.html must be packaged into static resources")
                .isTrue();
    }

    @Test
    void frontendAssetsBundleIsPackaged() {
        assertThat(new ClassPathResource("static/assets").exists())
                .as("Vite assets directory must be packaged")
                .isTrue();
    }
}
