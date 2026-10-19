                refuses(() -> DownloadChecks.metadata(new ByteArrayInputStream(
                        new byte[DownloadChecks.MAX_METADATA_BYTES + 1])));
            case "metadata_timestamp":
                check(metadata("build 9223372036854775807 FP6 stable")[1]
                        .equals(Long.toString(Long.MAX_VALUE)));
                check(metadata("build 00000000000000000001 FP6 stable")[1]
                        .equals("00000000000000000001"));
                for (String timestamp : new String[]{"9223372036854775808", "-1", "+1",
                        "\u0661\u0662\u0663", "\uff11\uff12\uff13", "1\u0662"}) {
                    refuses(() -> metadata("build " + timestamp + " FP6 stable"));
                }
                String prefix = "build ";
                String suffix = "1 FP6 stable";
                String boundary = prefix + "0".repeat(DownloadChecks.MAX_METADATA_BYTES
                        - prefix.length() - suffix.length()) + suffix;
                check(boundary.length() == DownloadChecks.MAX_METADATA_BYTES);
                check(metadata(boundary)[1].endsWith("1"));
                refuses(() -> metadata(boundary + "\n"));
                break;
