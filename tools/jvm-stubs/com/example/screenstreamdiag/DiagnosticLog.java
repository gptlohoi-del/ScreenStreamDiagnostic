package com.example.screenstreamdiag;
public final class DiagnosticLog {
    public static void event(String topic,String line){System.out.println("["+topic+"] "+line);}
}
