# Helpers for trying the service by hand. Source this file; do not run it:
#
#     source scripts/demo.sh
#
# Then:  begin   approve | decline | takeover   handback "what I did"   show
# Works in bash and zsh. The service must be running on localhost:8090 and the bank on localhost:8080.

API="${API:-http://localhost:8090}"
BANK="${BANK:-http://localhost:8080}"
RECIPE="${RECIPE:-parabank.open-savings-account}"
DEMO_PASSWORD="demo-pass-1"

# Variable names here are deliberately not USERNAME: in zsh that one is special (your login name)
# and assigning to it silently does nothing.

# Makes a new customer in the bank and prints the new username. Prints nothing if it failed.
register() {
  # Not $RANDOM: inside $( ... ) zsh starts it from the same seed every time, so every call
  # would invent the same name and the bank would refuse the second one.
  local name jar
  name="demo$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
  jar=$(mktemp)
  curl -s -c "$jar" -b "$jar" -o /dev/null "$BANK/parabank/register.htm"
  curl -s -c "$jar" -b "$jar" -L "$BANK/parabank/register.htm" \
    --data-urlencode "customer.firstName=Demo" --data-urlencode "customer.lastName=User" \
    --data-urlencode "customer.address.street=1 St" --data-urlencode "customer.address.city=Town" \
    --data-urlencode "customer.address.state=CA" --data-urlencode "customer.address.zipCode=90000" \
    --data-urlencode "customer.phoneNumber=555" --data-urlencode "customer.ssn=000-00-0000" \
    --data-urlencode "customer.username=$name" --data-urlencode "customer.password=$DEMO_PASSWORD" \
    --data-urlencode "repeatedPassword=$DEMO_PASSWORD" | grep -q "created successfully" && echo "$name"
}

# A short view of the current session.
show() {
  if [ -z "$SID" ]; then echo "no session yet: run begin"; return 1; fi
  curl -s "$API/sessions/$SID" | python3 -c "
import sys, json
v = json.load(sys.stdin)
if 'state' not in v:
    print('error:', v.get('status'), v.get('message') or v.get('error')); sys.exit()
r = v.get('result') or {}
i = v.get('intervention') or {}
print('state     :', v['state'], '|', v.get('resultType'))
if i: print('paused at :', i.get('stepId'), '-', (i.get('reason') or '')[:70])
if r.get('outputs'): print('outputs   :', r['outputs'])
if r.get('code'): print('answer    :', r['code'])
if r.get('kind'): print('kind      :', r['kind'], '| step', r.get('stepId'))
if r.get('observed'): print('observed  :', r['observed'][:160])
for t in v.get('humanTurns') or []: print('person did:', t['events'])
print('history   :', v['history'])"
}

# Run the AGENT on a goal (Claude drives the real bank; needs ANTHROPIC_API_KEY in the service's environment).
# It stops at the risky button (PAUSED). Run `approve`: Claude finishes and the recipe is saved in artifacts/.
# Then `begin` replays that saved recipe with no model.
discover() {
  CUSTOMER=$(register)
  if [ -z "$CUSTOMER" ]; then echo "could not register a customer: is the bank up on $BANK ?"; return 1; fi
  echo "customer: $CUSTOMER"
  SID=$(curl -s -X POST "$API/discover" -H 'Content-Type: application/json' -d "{
    \"goal\": \"Log in to the bank. Then open a new SAVINGS account: use the Open New Account page, choose the SAVINGS type, and press the Open New Account button. When the confirmation shows, read the new account number.\",
    \"inputs\": {\"username\": \"$CUSTOMER\", \"password\": \"$DEMO_PASSWORD\"},
    \"sensitiveInputs\": [\"password\"],
    \"outputs\": [\"newAccountNumber\"],
    \"capabilityId\": \"$RECIPE\",
    \"name\": \"Open a savings account\",
    \"description\": \"Logs in to ParaBank, opens a new savings account, and returns its account number.\",
    \"knownOutcomes\": [{\"code\": \"INVALID_CREDENTIALS\", \"description\": \"The bank rejected the login\",
                       \"text\": \"The username and password could not be verified.\"}]}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))")
  if [ -z "$SID" ]; then echo "could not start discovery: is the service running on $API ?"; return 1; fi
  echo "session : $SID"
  show
}

# New customer, then replay the saved recipe. It should stop at the risky button (state PAUSED).
begin() {
  CUSTOMER=$(register)
  if [ -z "$CUSTOMER" ]; then echo "could not register a customer: is the bank up on $BANK ?"; return 1; fi
  echo "customer: $CUSTOMER"
  SID=$(curl -s -X POST "$API/capabilities/$RECIPE/replay" -H 'Content-Type: application/json' \
    -d "{\"inputs\":{\"username\":\"$CUSTOMER\",\"password\":\"$DEMO_PASSWORD\"}}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('id',''))")
  if [ -z "$SID" ]; then echo "could not start: is the service running on $API ?"; return 1; fi
  echo "session : $SID"
  show
}

# Let the run press the button itself.
approve() {
  curl -s -X POST "$API/sessions/$SID/resume" -H 'Content-Type: application/json' -d '{"approve":true}' >/dev/null
  show
}

# Refuse: nothing is pressed, the run stops.
decline() {
  curl -s -X POST "$API/sessions/$SID/resume" -H 'Content-Type: application/json' -d '{"approve":false}' >/dev/null
  show
}

# Take the live window. Then use it, then run: handback "what I did"
takeover() {
  curl -s -X POST "$API/sessions/$SID/takeover" >/dev/null
  show
}

# Give the window back. The run then checks that the step's result is really on the page.
handback() {
  local note="${1:-handed back}"
  curl -s -X POST "$API/sessions/$SID/resume" -H 'Content-Type: application/json' \
    -d "{\"note\":\"$note\"}" >/dev/null
  show
}
