/**
 * Welcome to Cloudflare Workers! This is your first worker.
 *
 * - Run `npm run dev` in your terminal to start a development server
 * - Open a browser tab at http://localhost:8787/ to see your worker in action
 * - Run `npm run deploy` to publish your worker
 *
 * Bind resources to your worker in `wrangler.jsonc`. After adding bindings, a type definition for the
 * `Env` object can be regenerated with `npm run cf-typegen`.
 *
 * Learn more at https://developers.cloudflare.com/workers/
 */

export default {
	async fetch(request, env, ctx): Promise<Response> {
		const url = new URL(request.url);

		if (url.pathname !== '/webhook') {
			return new Response('Not found', { status: 404 });
		}

		if (request.method !== 'POST') {
			return new Response('Method not allowed', {
				status: 405,
				headers: { Allow: 'POST' },
			});
		}

		const deliveryId = request.headers.get('webhook-id');
		const timestamp = request.headers.get('webhook-timestamp');
		const signatures = request.headers.get('webhook-signature');

		if (!deliveryId || !timestamp || !signatures) {
			return new Response('Unauthorized', { status: 401 });
		}

		if (!/^\d+$/.test(timestamp)) {
			return new Response('Unauthorized', { status: 401 });
		}

		const timestampSeconds = Number(timestamp);
		const nowSeconds = Math.floor(Date.now() / 1000);

		if (!Number.isSafeInteger(timestampSeconds) || Math.abs(nowSeconds - timestampSeconds) > 300) {
			return new Response('Unauthorized', { status: 401 });
		}

		let key: CryptoKey;

		try {
			const secretBytes = decodeBase64(env.WEBHOOK_SIGNING_SECRET);

			if (secretBytes.length !== 32) {
				return new Response('Receiver configuration error', { status: 500 });
			}

			key = await crypto.subtle.importKey('raw', secretBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
		} catch {
			return new Response('Receiver configuration error', { status: 500 });
		}

		const body = new Uint8Array(await request.arrayBuffer());

		const prefix = new TextEncoder().encode(`${deliveryId}.${timestamp}.`);

		const signedContent = new Uint8Array(prefix.length + body.length);
		signedContent.set(prefix);
		signedContent.set(body, prefix.length);

		let verified = false;

		for (const candidate of signatures.trim().split(/\s+/)) {
			if (!candidate.startsWith('v1,')) {
				continue;
			}

			try {
				const signatureBytes = decodeBase64(candidate.slice(3));

				if (signatureBytes.length !== 32) {
					continue;
				}

				if (await crypto.subtle.verify('HMAC', key, signatureBytes, signedContent)) {
					verified = true;
					break;
				}
			} catch {
				// Skip malformed candidates and try the next one.
				continue;
			}
		}

		if (!verified) {
			return new Response('Unauthorized', { status: 401 });
		}


		console.info('Webhook verified', { deliveryId });

		return new Response(null, { status: 204 });

	},
} satisfies ExportedHandler<Env>;

function decodeBase64(value: string) {
	return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}
