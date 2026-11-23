import { createExecutionContext, waitOnExecutionContext, SELF } from 'cloudflare:test';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import worker from '../src/index';

// Same independent reference vectors used by Java's WebhookSignerTests.
const SECRET = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=';
const PREVIOUS_SECRET = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';
const ID = 'a92c7fcd-8e89-4ce0-9baf-745b7362dd50';
const TIMESTAMP = '1794630000';
const BODY = '{"orderId":128}';
const SIGNATURE = 'v1,7CclEDnWI2heBWWpeu+ugiW3rfNe+wwTkFraomzGBu4=';
const PREVIOUS_SIGNATURE = 'v1,yHO3jowv4qSYgTkqAJkR59UAYBgI+nqqT0Ixfq/2ZTA=';
const IncomingRequest = Request<unknown, IncomingRequestCfProperties>;

beforeEach(() => {
    vi.spyOn(Date, 'now').mockReturnValue(Number(TIMESTAMP) * 1000);
});

afterEach(() => {
    vi.restoreAllMocks();
});

function request(body = BODY, changes: Record<string, string> = {}) {
    return new IncomingRequest('https://receiver.example/webhook', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'webhook-id': ID,
            'webhook-timestamp': TIMESTAMP,
            'webhook-signature': SIGNATURE,
            ...changes,
        },
        body,
    });
}

async function invoke(incoming: InstanceType<typeof IncomingRequest>, bindings: Env = {
    WEBHOOK_SIGNING_SECRET: SECRET,
}) {
    const context = createExecutionContext();
    const response = await worker.fetch(incoming, bindings, context);
    await waitOnExecutionContext(context);
    return response;
}

// Additional payload fixtures are signed independently of the receiver implementation.
async function sign(body: string, timestamp = TIMESTAMP, id = ID) {
    const key = await crypto.subtle.importKey(
        'raw', Uint8Array.from(atob(SECRET), character => character.charCodeAt(0)),
        { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
    );
    const bytes = new Uint8Array(await crypto.subtle.sign(
        'HMAC', key, new TextEncoder().encode(`${id}.${timestamp}.${body}`),
    ));
    return `v1,${btoa(String.fromCharCode(...bytes))}`;
}

describe('Webhook receiver', () => {
    it('accepts the independent Java signer reference vector with an empty 204 response', async () => {
        const response = await invoke(request());
        expect(response.status).toBe(204);
        expect(await response.text()).toBe('');
    });

    it.each(['/', '/other', '/webhook/'])('rejects the wrong path %s', async path => {
        const response = await invoke(new IncomingRequest(`https://receiver.example${path}`, { method: 'POST' }));
        expect(response.status).toBe(404);
    });

    it.each(['GET', 'HEAD', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'])('rejects method %s and advertises POST', async method => {
        const response = await invoke(new IncomingRequest('https://receiver.example/webhook', { method }));
        expect(response.status).toBe(405);
        expect(response.headers.get('Allow')).toBe('POST');
    });

    it.each(['webhook-id', 'webhook-timestamp', 'webhook-signature'])('rejects a missing %s header', async header => {
        const incoming = request();
        incoming.headers.delete(header);
        expect((await invoke(incoming)).status).toBe(401);
    });

    it.each(['webhook-id', 'webhook-timestamp', 'webhook-signature'])('rejects an empty %s header', async header => {
        expect((await invoke(request(BODY, { [header]: '' }))).status).toBe(401);
    });

    it.each(['abc', '1e9', 'NaN', 'Infinity', '-1', '1794630000.5', '9007199254740992'])
        ('rejects malformed or unsafe timestamp %s', async timestamp => {
            expect((await invoke(request(BODY, { 'webhook-timestamp': timestamp }))).status).toBe(401);
        });

    it.each([
        [-301, 401], [-300, 204], [0, 204], [300, 204], [301, 401],
    ])('at a clock offset of %i seconds returns %i', async (offset, status) => {
        vi.mocked(Date.now).mockReturnValue((Number(TIMESTAMP) + offset) * 1000);
        expect((await invoke(request())).status).toBe(status);
    });

    it('rejects a changed payload with the original signature', async () => {
        expect((await invoke(request('{"orderId":129}'))).status).toBe(401);
    });

    it('rejects changed whitespace even when the JSON value is unchanged', async () => {
        expect((await invoke(request('{ "orderId": 128 }'))).status).toBe(401);
    });

    it('rejects a changed delivery ID', async () => {
        expect((await invoke(request(BODY, { 'webhook-id': 'another-delivery' }))).status).toBe(401);
    });

    it('rejects a changed timestamp even within the accepted time window', async () => {
        expect((await invoke(request(BODY, { 'webhook-timestamp': String(Number(TIMESTAMP) + 1) }))).status).toBe(401);
    });

    it('rejects a valid-length signature made with a different key', async () => {
        expect((await invoke(request(BODY, { 'webhook-signature': PREVIOUS_SIGNATURE }))).status).toBe(401);
    });

    it.each(['v2,abcd', 'v1,%%%', 'v1,', 'v1,YQ==', `v1,${SECRET},extra`, '   '])
        ('rejects an unsupported or malformed signature header %s', async signatures => {
            expect((await invoke(request(BODY, { 'webhook-signature': signatures }))).status).toBe(401);
        });

    it.each([SECRET, PREVIOUS_SECRET])('accepts either receiver key during rotation overlap', async secret => {
        const response = await invoke(request(BODY, {
            'webhook-signature': `${SIGNATURE} ${PREVIOUS_SIGNATURE}`,
        }), { WEBHOOK_SIGNING_SECRET: secret });
        expect(response.status).toBe(204);
    });

    it('continues past malformed, unsupported and nonmatching candidates to find a valid one', async () => {
        const signatures = `v1,%%% v2,ignored v1,YQ== ${PREVIOUS_SIGNATURE}  ${SIGNATURE}`;
        expect((await invoke(request(BODY, { 'webhook-signature': signatures }))).status).toBe(204);
    });

    it('accepts valid UTF-8 bytes without normalizing JSON formatting', async () => {
        const body = '{ "message": "caf\u00e9 \u4f60\u597d \ud83d\ude80", "orderId": 128 }\n';
        const signature = await sign(body);
        expect((await invoke(request(body, { 'webhook-signature': signature }))).status).toBe(204);
        expect((await invoke(request(body.trim(), { 'webhook-signature': signature }))).status).toBe(401);
    });

    it.each(['', 'not-valid-base64!', 'YQ=='])('returns a generic configuration error for invalid secret %s', async secret => {
        const response = await invoke(request(), { WEBHOOK_SIGNING_SECRET: secret });
        expect(response.status).toBe(500);
        expect(await response.text()).toBe('Receiver configuration error');
    });

    it('returns a generic configuration error when the secret binding is missing', async () => {
        // Deliberately model a runtime misconfiguration that violates the generated Env type.
        const response = await invoke(request(), {} as Env);
        expect(response.status).toBe(500);
        expect(await response.text()).toBe('Receiver configuration error');
    });

    it('authenticates through the deployed-style SELF entry point using a fixed test binding', async () => {
        vi.restoreAllMocks();
        const timestamp = String(Math.floor(Date.now() / 1000));
        const signatures = await sign(BODY, timestamp);
        const valid = await SELF.fetch(request(BODY, {
            'webhook-timestamp': timestamp,
            'webhook-signature': signatures,
        }));
        expect(valid.status).toBe(204);

        const tampered = await SELF.fetch(request('{"orderId":999}', {
            'webhook-timestamp': timestamp,
            'webhook-signature': signatures,
        }));
        expect(tampered.status).toBe(401);
    });
});
