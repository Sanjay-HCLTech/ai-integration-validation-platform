package com.hcl.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "payload")
public class PayloadProperties {

    private String root = "C:/payloads";
    private final Base base = new Base();
    private final Default defaultValue = new Default();
    private final Manifest manifest = new Manifest();

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public Base getBase() {
        return base;
    }

    public Default getDefault() {
        return defaultValue;
    }

    public Manifest getManifest() {
        return manifest;
    }

    public static class Base {
        private String dir;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class Default {
        private String file;

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }

    public static class Manifest {
        private String file;

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }
}
