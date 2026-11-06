import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A minimal server with only the files the site needs, so the container image holds no node_modules (ADR-0082).
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
