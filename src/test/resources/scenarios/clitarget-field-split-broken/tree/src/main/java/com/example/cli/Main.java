package com.example.cli;

import com.example.command.CheckCommand;
import com.example.command.ColumnCommand;
import com.example.command.CountCommand;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("unknown command: ");
            System.exit(3);
        }

        String command = args[0];
        String[] commandArgs = new String[args.length - 1];
        System.arraycopy(args, 1, commandArgs, 0, commandArgs.length);

        int exitCode;
        switch (command) {
            case "check":
                exitCode = new CheckCommand().execute(commandArgs);
                break;
            case "count":
                exitCode = new CountCommand().execute(commandArgs);
                break;
            case "column":
                exitCode = new ColumnCommand().execute(commandArgs);
                break;
            default:
                System.err.println("unknown command: " + command);
                System.exit(3);
                return;
        }

        System.exit(exitCode);
    }
}