package com.r3.corda.lib.tokens.selection.memory.services

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.withoutIssuer
import com.r3.corda.lib.tokens.selection.InsufficientBalanceException
import com.r3.corda.lib.tokens.selection.memory.internal.Holder
import com.r3.corda.lib.tokens.selection.memory.internal.lookupExternalIdFromKey
import com.r3.corda.lib.tokens.selection.sortByStateRefAscending
import net.corda.core.contracts.Amount
import net.corda.core.contracts.StateAndRef
import net.corda.core.internal.uncheckedCast
import net.corda.core.node.AppServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.DEFAULT_PAGE_NUM
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.contextLogger
import rx.Observable
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

val UNLOCKER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
val UPDATER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
val EMPTY_BUCKET = TokenBucket()

const val PLACE_HOLDER: String = "THIS_IS_A_PLACE_HOLDER"

@CordaService
class VaultWatcherService(private val tokenObserver: TokenObserver, private val serviceHub: ServiceHub) : SingletonSerializeAsToken() {

    private val __backingMap: ConcurrentMap<StateAndRef<FungibleToken>, String> = ConcurrentHashMap()
    private val __indexed: ConcurrentMap<Class<out Holder>, ConcurrentMap<TokenIndex, TokenBucket>> = ConcurrentHashMap()

    private val indexViewCreationLock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    enum class IndexingType(val ownerType: Class<out Holder>) {

        EXTERNAL_ID(Holder.MappedIdentity::class.java),
        PUBLIC_KEY(Holder.KeyIdentity::class.java);

        companion object {
            fun fromHolder(holder: Class<out Holder>): IndexingType {
                return when (holder) {
                    Holder.MappedIdentity::class.java -> {
                        EXTERNAL_ID
                    }

                    Holder.KeyIdentity::class.java -> {
                        PUBLIC_KEY;
                    }
                    else -> throw IllegalArgumentException("Unknown Holder type: $holder")
                }
            }
        }

    }

    constructor(appServiceHub: AppServiceHub) : this(getObservableFromAppServiceHub(appServiceHub), appServiceHub)

    companion object {
        val LOG = contextLogger()

        private fun getObservableFromAppServiceHub(appServiceHub: AppServiceHub): TokenObserver {
            val ownerProvider: (StateAndRef<FungibleToken>, ServiceHub, IndexingType) -> Holder = { token, serviceHub, indexingType ->
                when (indexingType) {
                    IndexingType.PUBLIC_KEY -> Holder.KeyIdentity(token.state.data.holder.owningKey)
                    IndexingType.EXTERNAL_ID -> {
                        val owningKey = token.state.data.holder.owningKey
                        lookupExternalIdFromKey(owningKey, serviceHub)
                    }
                }
            }
            val pageSize = 1000
            var currentPage = DEFAULT_PAGE_NUM
            var (existingStates, observable) = appServiceHub.vaultService.trackBy(
                    contractStateType = FungibleToken::class.java,
                    paging = PageSpecification(pageNumber = currentPage, pageSize = pageSize),
                    criteria = QueryCriteria.VaultQueryCriteria(),
                    sorting = sortByStateRefAscending())
            val statesToProcess = mutableListOf<StateAndRef<FungibleToken>>()
            while (existingStates.states.isNotEmpty()) {
                statesToProcess.addAll(uncheckedCast(existingStates.states))
                existingStates = appServiceHub.vaultService.queryBy(
                        contractStateType = FungibleToken::class.java,
                        paging = PageSpecification(pageNumber = ++currentPage, pageSize = pageSize)
                )
            }
            return TokenObserver(statesToProcess, uncheckedCast(observable), ownerProvider)
        }
    }

    init {
        addTokensToCache(tokenObserver.initialValues)
        tokenObserver.source.subscribe(::onVaultUpdate)
    }

    fun processToken(token: StateAndRef<FungibleToken>, indexingType: IndexingType): TokenIndex {
        val owner = tokenObserver.ownerProvider(token, serviceHub, indexingType)
        val type = token.state.data.amount.token.tokenType.tokenClass
        val typeId = token.state.data.amount.token.tokenType.tokenIdentifier
        return TokenIndex(owner, type, typeId)
    }

    private fun onVaultUpdate(t: Vault.Update<FungibleToken>) {
//        UPDATER.submit {
        removeTokensFromCache(t.consumed)
        addTokensToCache(t.produced)
//        }
    }

    private fun removeTokensFromCache(stateAndRefs: Collection<StateAndRef<FungibleToken>>) {
        indexViewCreationLock.read {
            for (stateAndRef in stateAndRefs) {
                val existingMark = __backingMap.remove(stateAndRef)
                existingMark
                        ?: LOG.warn("Attempted to remove existing token ${stateAndRef.ref}, but it was not found this suggests incorrect vault behaviours")
                for (key in __indexed.keys) {
                    val index = processToken(stateAndRef, IndexingType.fromHolder(key))
                    val indexedViewForHolder = __indexed[key]
                    indexedViewForHolder
                            ?: LOG.warn("tried to obtain an indexed view for holder type: $key but was not found in set of indexed views")

                    val bucketForIndex: TokenBucket? = indexedViewForHolder?.get(index)
                    bucketForIndex?.remove(stateAndRef)
                }
            }
        }
    }

    private fun addTokensToCache(stateAndRefs: Collection<StateAndRef<FungibleToken>>) {
        indexViewCreationLock.read {
            for (stateAndRef in stateAndRefs) {
                val existingMark = __backingMap.putIfAbsent(stateAndRef, PLACE_HOLDER)
                existingMark?.let {
                    LOG.warn("Attempted to overwrite existing token ${stateAndRef.ref}, this suggests incorrect vault behaviours")
                }
                for (key in __indexed.keys) {
                    val index = processToken(stateAndRef, IndexingType.fromHolder(key))
                    val indexedViewForHolder = __indexed[key]
                            ?: throw IllegalStateException("tried to obtain an indexed view for holder type: $key but was not found in set of indexed views")
                    val bucketForIndex: TokenBucket = indexedViewForHolder.computeIfAbsent(index) {
                        TokenBucket()
                    }
                    bucketForIndex.add(stateAndRef)
                }
            }
        }
    }

    private fun getOrCreateIndexViewForHolderType(holderType: Class<out Holder>): ConcurrentMap<TokenIndex, TokenBucket> {
        return __indexed[holderType] ?: indexViewCreationLock.write {
            __indexed[holderType] ?: generateNewIndexedView(holderType)
        }
    }

    private fun generateNewIndexedView(holderType: Class<out Holder>): ConcurrentMap<TokenIndex, TokenBucket> {
        val indexedViewForHolder: ConcurrentMap<TokenIndex, TokenBucket> = ConcurrentHashMap()
        for (stateAndRef in __backingMap.keys) {
            val index = processToken(stateAndRef, IndexingType.fromHolder(holderType))
            val bucketForIndex: TokenBucket = indexedViewForHolder.computeIfAbsent(index) {
                TokenBucket()
            }
            bucketForIndex.add(stateAndRef)
        }
        __indexed[holderType] = indexedViewForHolder
        return indexedViewForHolder
    }

    fun lockTokensExternal(list: List<StateAndRef<FungibleToken>>, knownSelectionId: String) {
        list.forEach {
            __backingMap.replace(it, PLACE_HOLDER, knownSelectionId)
        }
    }

    fun selectTokens(
            owner: Holder,
            requiredAmount: Amount<IssuedTokenType>,
            predicate: ((StateAndRef<FungibleToken>) -> Boolean) = { true },
            allowShortfall: Boolean = false,
            autoUnlockDelay: Duration = Duration.ofMinutes(5),
            selectionId: String
    ): List<StateAndRef<FungibleToken>> {

        val lockedTokens = mutableListOf<StateAndRef<FungibleToken>>()
        val bucket: Iterable<StateAndRef<FungibleToken>>
        if (owner is Holder.TokenOnly) {
            bucket = __backingMap.keys
        } else {
            val indexedView = getOrCreateIndexViewForHolderType(owner.javaClass)
            bucket = getTokenBucket(owner, requiredAmount.token.tokenClass, requiredAmount.token.tokenIdentifier, indexedView)
        }

        var amountLocked: Amount<IssuedTokenType> = requiredAmount.copy(quantity = 0)
        for (tokenStateAndRef in bucket) {
            // Does the token satisfy the (optional) predicate eg. issuer?
            if (predicate.invoke(tokenStateAndRef)) {
                // if so, race to lock the token, expected oldValue = PLACE_HOLDER
                if (__backingMap.replace(tokenStateAndRef, PLACE_HOLDER, selectionId)) {
                    // we won the race to lock this token
                    lockedTokens.add(tokenStateAndRef)
                    val token = tokenStateAndRef.state.data
                    amountLocked += uncheckedCast(token.amount.withoutIssuer())
                    if (amountLocked >= requiredAmount) {
                        break
                    }
                }
            }
        }

        if (!allowShortfall && amountLocked < requiredAmount) {
            lockedTokens.forEach {
                unlockToken(it, selectionId)
            }
            throw InsufficientBalanceException("Insufficient spendable states identified for $requiredAmount.")
        }

        UNLOCKER.schedule({
            lockedTokens.forEach {
                unlockToken(it, selectionId)
            }
        }, autoUnlockDelay.toMillis(), TimeUnit.MILLISECONDS)

        return uncheckedCast(lockedTokens)
    }

    fun unlockToken(it: StateAndRef<FungibleToken>, selectionId: String) {
        __backingMap.replace(it, selectionId, PLACE_HOLDER)
    }

    private fun getTokenBucket(idx: Holder,
                               tokenClass: Class<*>,
                               tokenIdentifier: String,
                               mapToSelectFrom: ConcurrentMap<TokenIndex, TokenBucket>): TokenBucket {
        return mapToSelectFrom[TokenIndex(idx, tokenClass, tokenIdentifier)] ?: EMPTY_BUCKET
    }

}

data class TokenObserver(val initialValues: List<StateAndRef<FungibleToken>>,
                         val source: Observable<Vault.Update<FungibleToken>>,
                         val ownerProvider: ((StateAndRef<FungibleToken>, ServiceHub, VaultWatcherService.IndexingType) -> Holder))

class TokenBucket(set: MutableSet<StateAndRef<FungibleToken>> = ConcurrentHashMap<StateAndRef<FungibleToken>, Boolean>().keySet(true)) : MutableSet<StateAndRef<FungibleToken>> by set


data class TokenIndex(val owner: Holder, val tokenClazz: Class<*>, val tokenIdentifier: String)
