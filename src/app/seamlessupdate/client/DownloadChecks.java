    static final int MAX_METADATA_BYTES = 4096;
        byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("Update information is too large");
        }
        if (!line.matches("[A-Za-z0-9_.-]{1,128} [0-9]+ [A-Za-z0-9_-]{1,64} [A-Za-z0-9_-]{1,64}")) {
        String[] metadata = line.split(" ");
        try {
            Long.parseLong(metadata[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Malformed build date in update information", e);
        }
        return metadata;
