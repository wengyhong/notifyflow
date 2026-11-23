import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
	plugins: [
		cloudflareTest({
			wrangler: { configPath: "./wrangler.jsonc" },
			// Public fixture only: tests must not depend on .dev.vars credentials.
			miniflare: {
				bindings: {
					WEBHOOK_SIGNING_SECRET: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
				},
			},
		}),
	],
});
