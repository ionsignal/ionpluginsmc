package com.ionsignal.minecraft.ioncore.network;

/**
 * Defines the mechanical routing protocol prefixes for NATS communication.
 * Mirrors the TypeScript @ionsignal/ionutils package.
 */
public final class SubjectTaxonomy {
    private SubjectTaxonomy() {
    }

    public static final class SubjectPrefix {
        public static final String COMMAND = "ion.cmd";
        public static final String EVENT = "ion.evt";
        public static final String REQUEST = "ion.req";
    }

    public static final class GlobalSubjectPrefix {
        public static final String EVENT = "gbl.evt";
        public static final String REQUEST = "gbl.req";
    }
}