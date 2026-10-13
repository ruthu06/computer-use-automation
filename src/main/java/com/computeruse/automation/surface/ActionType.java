package com.computeruse.automation.surface;

/** Every thing automation can do to a surface. The policy allowlist is expressed in these. */
public enum ActionType {
    NAVIGATE,   // go to a URL
    CLICK,      // click a control
    TYPE,       // type text into a field
    SELECT,     // pick an option from a dropdown
    READ,       // read the text of a control and return it
    WAIT        // wait until a condition is true
}
