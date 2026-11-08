import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, writeBatch, arrayUnion } from 'firebase/firestore';
import fs from 'fs';

const env = await initializeTestEnvironment({
  projectId: 'demo-money',
  firestore: { rules: fs.readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8085 }
});

// h1: Tobin's household. h2: a stranger's household.
await env.withSecurityRulesDisabled(async c => {
  const db = c.firestore();
  await setDoc(doc(db, 'households', 'h1'), { householdId: 'h1', name: 'Home', ownerUid: 'u1', memberUids: ['u1'], members: { u1: { firstName: 'Tobin' } } });
  await setDoc(doc(db, 'households', 'h1', 'transactions', 't1'), { amount: 10 });
  await setDoc(doc(db, 'households', 'h2'), { householdId: 'h2', name: 'Other', ownerUid: 'u5', memberUids: ['u5'], members: { u5: { firstName: 'Eve' } } });
  await setDoc(doc(db, 'households', 'h2', 'transactions', 'x1'), { amount: 99 });
  await setDoc(doc(db, 'households', 'h1', 'GeminiInteractionLogs', 'g1'), { rawSmsContent: 'x' });
});

const ctx = (uid, email, verified = true) => env.authenticatedContext(uid, { email, email_verified: verified }).firestore();
const owner = ctx('u1', 'Tobin@Example.com');
const spouse = ctx('u2', 'spouse@example.com');
const unverified = ctx('u1', 'tobin@example.com', false);
const stranger = ctx('u5', 'evil@example.com');
const newbie = ctx('u7', 'new@example.com');
const anon = env.unauthenticatedContext().firestore();

let fails = 0;
async function t(name, p) { try { await p; console.log('PASS', name); } catch (e) { fails++; console.log('FAIL', name, e.message); } }
const tx = (db, h, id) => doc(db, 'households', h, 'transactions', id);
const joinUpdate = (uid, name) => ({ memberUids: arrayUnion(uid), [`members.${uid}`]: { firstName: name } });

// Household data
await t('member reads tx', assertSucceeds(getDoc(tx(owner, 'h1', 't1'))));
await t('member lists tx', assertSucceeds(getDocs(collection(owner, 'households', 'h1', 'transactions'))));
await t('member writes tx', assertSucceeds(setDoc(tx(owner, 'h1', 't2'), { amount: 5 })));
await t('member reads household', assertSucceeds(getDoc(doc(owner, 'households', 'h1'))));
await t('unverified denied', assertFails(getDoc(tx(unverified, 'h1', 't1'))));
await t('stranger cannot read other household tx', assertFails(getDocs(collection(stranger, 'households', 'h1', 'transactions'))));
await t('stranger cannot write other household tx', assertFails(setDoc(tx(stranger, 'h1', 'evil'), { amount: 1 })));
await t('member cannot read other household', assertFails(getDoc(tx(owner, 'h2', 'x1'))));
await t('anon denied', assertFails(getDocs(collection(anon, 'households', 'h1', 'transactions'))));
await t('member adds a shared reserve', assertSucceeds(setDoc(doc(owner, 'households', 'h1', 'reserves', 'r1'), { name: 'Insurance', amount: 12000 })));
await t('member reads shared reserves', assertSucceeds(getDocs(collection(owner, 'households', 'h1', 'reserves'))));
await t('stranger cannot read reserves', assertFails(getDocs(collection(stranger, 'households', 'h1', 'reserves'))));
await t('unknown subcollection denied', assertFails(getDoc(doc(owner, 'households', 'h1', 'GeminiInteractionLogs', 'g1'))));
await t('unknown top-level collection denied', assertFails(getDoc(doc(owner, 'Transactions', 't1'))));

// Creating a household (batch with profile, as the app does)
{
  const b = writeBatch(newbie);
  b.set(doc(newbie, 'households', 'h3'), { householdId: 'h3', name: 'New', ownerUid: 'u7', memberUids: ['u7'], members: { u7: { firstName: 'New' } } });
  b.set(doc(newbie, 'users', 'u7'), { householdId: 'h3' });
  await t('create own household + profile', assertSucceeds(b.commit()));
}
await t('new owner seeds accounts', assertSucceeds(setDoc(doc(newbie, 'households', 'h3', 'accounts', 'Food'), { accountType: 'EXPENSE' })));
await t('cannot create household with someone else in it', assertFails(setDoc(doc(newbie, 'households', 'h4'), { householdId: 'h4', ownerUid: 'u7', memberUids: ['u7', 'u1'], members: { u7: {} } })));
await t('cannot create household owned by someone else', assertFails(setDoc(doc(newbie, 'households', 'h5'), { householdId: 'h5', ownerUid: 'u1', memberUids: ['u7'], members: { u7: {} } })));

// Joining without an invite
await t('cannot join without invite', assertFails(updateDoc(doc(stranger, 'households', 'h1'), joinUpdate('u5', 'Eve'))));

// Invites
const invite = (db, email, hid, by) => setDoc(doc(db, 'invites', email), { email, householdId: hid, householdName: 'Home', invitedByUid: by, invitedByName: 'Tobin', createdAt: 1 });
await t('stranger cannot invite into h1', assertFails(invite(stranger, 'spouse@example.com', 'h1', 'u5')));
await t('member invites partner', assertSucceeds(invite(owner, 'spouse@example.com', 'h1', 'u1')));
await t('member re-sends own invite', assertSucceeds(invite(owner, 'spouse@example.com', 'h1', 'u1')));
await t('other household cannot take over invite', assertFails(invite(stranger, 'spouse@example.com', 'h2', 'u5')));
await t('invite must be lower-case', assertFails(invite(owner, 'Mixed@Example.com', 'h1', 'u1')));
await t('invitee reads own invite', assertSucceeds(getDoc(doc(spouse, 'invites', 'spouse@example.com'))));
await t('user reads own missing invite', assertSucceeds(getDoc(doc(newbie, 'invites', 'new@example.com'))));
await t('stranger cannot read invite', assertFails(getDoc(doc(stranger, 'invites', 'spouse@example.com'))));

// Joining with an invite
await t('invitee cannot add someone else', assertFails(updateDoc(doc(spouse, 'households', 'h1'), joinUpdate('u9', 'X'))));
await t('invitee cannot rename while joining', assertFails(updateDoc(doc(spouse, 'households', 'h1'), { ...joinUpdate('u2', 'Spouse'), name: 'Mine' })));
await t('invitee joins', assertSucceeds(updateDoc(doc(spouse, 'households', 'h1'), joinUpdate('u2', 'Spouse'))));
await t('joined spouse writes profile', assertSucceeds(setDoc(doc(spouse, 'users', 'u2'), { householdId: 'h1' })));
await t('joined spouse deletes used invite', assertSucceeds(deleteDoc(doc(spouse, 'invites', 'spouse@example.com'))));
await t('joined spouse reads tx', assertSucceeds(getDoc(tx(spouse, 'h1', 't1'))));

// Member updates
await t('member renames household', assertSucceeds(updateDoc(doc(spouse, 'households', 'h1'), { name: 'Our home' })));
await t('member cannot edit partner entry', assertFails(updateDoc(doc(spouse, 'households', 'h1'), { 'members.u1': { firstName: 'X' } })));
await t('member cannot change ownerUid', assertFails(updateDoc(doc(spouse, 'households', 'h1'), { ownerUid: 'u2' })));
await t('member cannot add others to memberUids', assertFails(updateDoc(doc(owner, 'households', 'h1'), { memberUids: arrayUnion('u5') })));
await t('non-owner cannot delete household', assertFails(deleteDoc(doc(spouse, 'households', 'h1'))));

// Profiles
await t('own profile read', assertSucceeds(getDoc(doc(owner, 'users', 'u1'))));
await t('other profile read denied', assertFails(getDoc(doc(owner, 'users', 'u2'))));
await t('other profile write denied', assertFails(setDoc(doc(owner, 'users', 'u2'), { householdId: 'h2' })));

await env.cleanup();
console.log(fails ? `${fails} FAILED` : 'ALL PASSED');
process.exit(fails ? 1 : 0);
