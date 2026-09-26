const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/discovery-dom.js', 'utf8');
function run(links, readyState = 'complete', title = 'Page') {
    const document = {readyState, title, querySelectorAll: selector => selector.startsWith('meta') ? [] : links};
    return vm.runInNewContext(script, {document, TextEncoder, window: {matchMedia: media => ({matches: media !== 'print'})}});
}
function link(rel, href, media = '') {
    return {rel, href: new URL(href, 'https://example.com/base/').href, media, type: 'application/manifest+json',
        referrerPolicy: '', getAttribute: name => name === 'crossorigin' ? 'anonymous' : ''};
}
test('document order, media matching, resolved base URL and metadata', () => {
    const result = JSON.parse(run([link('manifest', 'first', 'print'), link('manifest', 'second'), link('icon', 'icon.png')]));
    assert.equal(result.manifests.find(item => item.mediaMatches).url, 'https://example.com/base/second');
    assert.equal(result.manifests[0].crossorigin, 'anonymous');
    assert.equal(result.manifests[0].referrerPolicy, 'no-referrer');
    assert.equal(result.icons.length, 1);
    assert.equal(run([], 'loading'), 'mws-dom-pending');
});
test('node caps and byte truncation preserve unusable candidate position', () => {
    const result = JSON.parse(run([...Array.from({length: 20}, () => link('manifest', 'm')), ...Array.from({length: 140}, () => link('icon', 'i'))]));
    assert.equal(result.manifests.length, 16);
    assert.equal(result.icons.length, 128);
    const truncated = JSON.parse(run([link('manifest', 'a'.repeat(8193)), link('manifest', 'valid')], 'complete', 'é'.repeat(4097)));
    assert.equal(truncated.title, null);
    assert.equal(truncated.manifests[0].usable, false);
    assert.equal(truncated.manifests[0].url, null);
});
test('oversized snapshot fails without a partial result', () => {
    const links = Array.from({length: 128}, () => {
        const item = link('icon', 'a'.repeat(8000));
        item.type = 'b'.repeat(8192);
        return item;
    });
    assert.equal(run(links), null);
});

test('explicit link policy overrides conservative document fallback', () => {
    const item = link('manifest', 'm');
    item.referrerPolicy = 'same-origin';
    assert.equal(JSON.parse(run([item])).manifests[0].referrerPolicy, 'same-origin');
});
