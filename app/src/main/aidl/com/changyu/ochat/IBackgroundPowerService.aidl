package com.changyu.ochat;

// Deliberately no command, path or package argument.
interface IBackgroundPowerService {
    int setExempt(boolean enabled, boolean allowRemoval) = 0;
    void destroy() = 16777114;
}
