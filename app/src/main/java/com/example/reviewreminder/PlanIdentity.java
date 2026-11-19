package com.example.reviewreminder;

import org.json.JSONObject;

/** Whether two task records are the same plan. A rename does not change this. */
public final class PlanIdentity {
    private PlanIdentity(){}

    public static boolean same(JSONObject a,JSONObject b){
        return a!=null&&b!=null&&a.optLong("created")==b.optLong("created");
    }
}
